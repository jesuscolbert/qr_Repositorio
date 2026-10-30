package bo.soboce.comedor;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

final class PeerDatabase extends SQLiteOpenHelper implements QuorumLog.Storage {
    final PeerConfig config;
    PeerDatabase(Context context, PeerConfig config) throws Exception {
        this(context,config,"comedor-peers.db");
    }
    PeerDatabase(Context context, PeerConfig config,String databaseName) throws Exception {
        super(context,databaseName,null,3); this.config=config;
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            String cluster=meta("cluster","");
            PeerConfig.require(cluster.isEmpty() || cluster.equals(config.cluster),"Este teléfono ya pertenece a otra red. Conserva su historial.");
            if(cluster.isEmpty()) {
                putMeta("cluster",config.cluster);putMeta("node",Integer.toString(config.node));putMeta("applied","0");putMeta("ballot","0");
                JSONArray people=config.seed.getJSONArray("people");
                for(int i=0;i<people.length();i++) save("people",people.getJSONObject(i).getString("id"),people.getJSONObject(i));
            }
            PeerConfig.require(meta("node","").equals(Integer.toString(config.node)),"No se puede cambiar la identidad de esta base.");
            if(!meta("projectionVersion","").equals("2")) {
                // Rebuild derived views only; never discard promises, votes or the original log.
                for(String table:new String[]{"people","meals","results"})db.delete(table,null,null);
                JSONArray people=config.seed.getJSONArray("people");
                for(int i=0;i<people.length();i++)save("people",people.getJSONObject(i).getString("id"),people.getJSONObject(i));
                putMeta("applied","0");applyCommitted();putMeta("projectionVersion","2");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    @Override public void onConfigure(SQLiteDatabase db){db.execSQL("PRAGMA synchronous=FULL");}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
        db.execSQL("CREATE TABLE acceptors(slot INTEGER PRIMARY KEY,promised INTEGER NOT NULL,ballot INTEGER NOT NULL,value TEXT)");
        db.execSQL("CREATE TABLE decisions(slot INTEGER PRIMARY KEY,ballot INTEGER NOT NULL,value TEXT NOT NULL,proof TEXT NOT NULL)");
        for(String name:new String[]{"people","meals","results"}) db.execSQL("CREATE TABLE "+name+"(id TEXT PRIMARY KEY,json TEXT NOT NULL)");
        OfflineLedger.create(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int version){
        if(old<1||old>2||version!=3)throw new IllegalStateException("Se requiere migración sin pérdida de datos.");
        OfflineLedger.create(db);
        // The constructor replays after SQLiteOpenHelper has finished opening the database.
        if(old==1)db.execSQL("DELETE FROM meta WHERE k='projectionVersion'");
    }
    String meta(String key,String fallback){try(Cursor c=getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k=?",new String[]{key})){return c.moveToFirst()?c.getString(0):fallback;}}
    void putMeta(String key,String value){ContentValues cv=new ContentValues();cv.put("k",key);cv.put("v",value);getWritableDatabase().insertWithOnConflict("meta",null,cv,SQLiteDatabase.CONFLICT_REPLACE);}
    @Override public synchronized long nextBallot(int node,long floor){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try { long n=Math.max(Long.parseLong(meta("ballot","0"))+1,floor/4+1);putMeta("ballot",Long.toString(n));db.setTransactionSuccessful();return n*4+node; }
        finally {db.endTransaction();}
    }
    @Override public synchronized long applied(){return Long.parseLong(meta("applied","0"));}
    private QuorumLog.Promise state(long slot){try(Cursor c=getReadableDatabase().rawQuery("SELECT promised,ballot,value FROM acceptors WHERE slot=?",new String[]{Long.toString(slot)})){return c.moveToFirst()?new QuorumLog.Promise(true,c.getLong(0),c.getLong(1),c.isNull(2)?null:c.getString(2)):new QuorumLog.Promise(true,0,0,null);}}
    private void state(long slot,long promised,long ballot,String value){ContentValues cv=new ContentValues();cv.put("slot",slot);cv.put("promised",promised);cv.put("ballot",ballot);cv.put("value",value);getWritableDatabase().insertWithOnConflict("acceptors",null,cv,SQLiteDatabase.CONFLICT_REPLACE);}
    synchronized QuorumLog.Promise prepare(long slot,long ballot){
        checkSlot(slot,ballot);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{var s=state(slot);if(ballot<s.promised())return new QuorumLog.Promise(false,s.promised(),s.acceptedBallot(),s.value());state(slot,ballot,s.acceptedBallot(),s.value());db.setTransactionSuccessful();return new QuorumLog.Promise(true,ballot,s.acceptedBallot(),s.value());}
        finally{db.endTransaction();}
    }
    synchronized QuorumLog.Vote accept(long slot,long ballot,String value) throws Exception {
        checkSlot(slot,ballot);PeerConfig.require(value.length()<24000,"Operación demasiado grande.");
        JSONObject command=config.command(value);
        PeerConfig.require(command.getInt("actor")>=0&&command.getInt("actor")<4,"Origen inválido.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            var s=state(slot);
            if(ballot<s.promised()||(s.acceptedBallot()==ballot&&s.value()!=null&&!s.value().equals(value)))return null;
            try(Cursor c=db.rawQuery("SELECT value FROM decisions WHERE slot=?",new String[]{Long.toString(slot)})){if(c.moveToFirst()&&!c.getString(0).equals(value))throw new IllegalStateException("Conflicto de historial.");}
            state(slot,ballot,ballot,value);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return new QuorumLog.Vote(config.node,config.vote(config.node,slot,ballot,value));
    }
    void checkSlot(long slot,long ballot){PeerConfig.require(slot>0&&slot<=applied()+512&&ballot>0&&ballot<Long.MAX_VALUE/2,"Sincroniza el historial antes de votar.");}
    static JSONObject encode(QuorumLog.Decision d) throws Exception {
        JSONArray votes=new JSONArray();for(var v:d.votes())votes.put(new JSONObject().put("node",v.node()).put("signature",v.signature()));
        return new JSONObject().put("slot",d.slot()).put("ballot",d.ballot()).put("value",d.value()).put("votes",votes);
    }
    static QuorumLog.Decision decode(JSONObject d) throws Exception {
        List<QuorumLog.Vote> votes=new ArrayList<>();JSONArray a=d.getJSONArray("votes");
        for(int i=0;i<a.length();i++){JSONObject v=a.getJSONObject(i);votes.add(new QuorumLog.Vote(v.getInt("node"),v.getString("signature")));}
        return new QuorumLog.Decision(d.getLong("slot"),d.getLong("ballot"),d.getString("value"),votes);
    }
    @Override public synchronized void learn(QuorumLog.Decision d) throws Exception {
        checkSlot(d.slot(),d.ballot());Set<Integer> voters=new HashSet<>();
        for(var v:d.votes())PeerConfig.require(v.node()>=0&&v.node()<4&&voters.add(v.node())&&config.validVote(v.node(),d.slot(),d.ballot(),d.value(),v.signature()),"Confirmación inválida.");
        PeerConfig.require(voters.size()>=3,"Falta mayoría.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT value FROM decisions WHERE slot=?",new String[]{Long.toString(d.slot())})){if(c.moveToFirst())PeerConfig.require(c.getString(0).equals(d.value()),"Historial incompatible.");}
            ContentValues row=new ContentValues();row.put("slot",d.slot());row.put("ballot",d.ballot());row.put("value",d.value());row.put("proof",encode(d).getJSONArray("votes").toString());db.insertWithOnConflict("decisions",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            // A learned value must also survive a later prepare on this acceptor.
            var old=state(d.slot());
            PeerConfig.require(old.value()==null||old.acceptedBallot()<d.ballot()||old.value().equals(d.value()),"Aceptación incompatible: detén y revisa este equipo.");
            state(d.slot(),Math.max(old.promised(),d.ballot()),Math.max(old.acceptedBallot(),d.ballot()),d.value());
            applyCommitted();
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private void applyCommitted() throws Exception {
        long next=applied()+1;
        while(true){
            String value;
            try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM decisions WHERE slot=?",new String[]{Long.toString(next)})){if(!c.moveToFirst())break;value=c.getString(0);}
            JSONObject command=config.command(value),req=command.getJSONObject("request");String id=req.getString("requestId");
            if(get("results",id)==null)save("results",id,new MealLedger(this,config).execute(command).put("commandHash",PeerConfig.hash(value)));
            putMeta("applied",Long.toString(next++));
        }
    }
    synchronized JSONArray history(long after,int limit) throws Exception {
        JSONArray result=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT slot,ballot,value,proof FROM decisions WHERE slot>? ORDER BY slot LIMIT ?",new String[]{Long.toString(after),Integer.toString(Math.min(limit,20))})){
            while(c.moveToNext())result.put(new JSONObject().put("slot",c.getLong(0)).put("ballot",c.getLong(1)).put("value",c.getString(2)).put("votes",new JSONArray(c.getString(3))));
        }return result;
    }
    synchronized JSONObject get(String table,String id) throws Exception {try(Cursor c=getReadableDatabase().rawQuery("SELECT json FROM "+table+" WHERE id=?",new String[]{id})){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}}
    synchronized List<JSONObject> rows(String table) throws Exception {List<JSONObject> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT json FROM "+table+" ORDER BY rowid",null)){while(c.moveToNext())out.add(new JSONObject(c.getString(0)));}return out;}
    void save(String table,String id,JSONObject data){ContentValues cv=new ContentValues();cv.put("id",id);cv.put("json",data.toString());getWritableDatabase().insertWithOnConflict(table,null,cv,SQLiteDatabase.CONFLICT_REPLACE);}
}
