package bo.soboce.comedor;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;

/** Private installers contain one identity. The public APK contains no enrolment asset. */
final class BundledSetup {
    static final String ASSET="comedor-setup.json";
    static void install(Context context,Vault vault) throws Exception {
        String text=vault.get("setupPending");
        if(text.isEmpty())try(InputStream in=context.getAssets().open(ASSET)){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){if(bytes.size()+n>2097152)throw new IOException("Instalador demasiado grande.");bytes.write(buffer,0,n);}text=bytes.toString("UTF-8");
        }catch(FileNotFoundException noBundle){return;}
        apply(context,vault,text);
    }
    static void apply(Context context,Vault vault,String text) throws Exception {
        PeerConfig target=new PeerConfig(text);String previous=vault.get("peerConfig");PeerConfig old=previous.isEmpty()?null:new PeerConfig(previous);
        if(old!=null){
            PeerConfig.require(old.cluster.equals(target.cluster),"Esta APK pertenece a otra red. Conserva la instalación y sus datos.");
            if(old.node==target.node){if(!vault.get("setupPending").isEmpty())vault.put("mode","p2p");vault.remove("setupPending");return;}
        }
        PeerConfig.require(vault.get("pending").isEmpty(),"Hay una entrega pendiente. Resuélvela en la instalación actual antes de cambiar de encargado.");
        PeerConfig.require(PeerService.engine==null,"Cierra la app y vuelve a abrirla para aplicar el instalador de este encargado.");
        File file=context.getDatabasePath("comedor-peers.db");
        // Stage before the database update. A process death resumes this same assignment on next launch.
        if(file.exists())try(SQLiteDatabase sql=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){
            sql.beginTransaction();try{
                PeerConfig.require(meta(sql,"cluster").equals(target.cluster),"La base pertenece a otra red. No se modificó el historial.");
                String node=meta(sql,"node");
                PeerConfig.require(node.equals(Integer.toString(target.node))||(old!=null&&node.equals(Integer.toString(old.node))),"La identidad de la base requiere revisión. No borres los datos.");
                for(String table:new String[]{"acceptors","decisions","meals","offline_events"})
                    PeerConfig.require(empty(sql,table),"Este teléfono ya tiene historial de otro encargado. Instala su APK correspondiente; no borres los datos.");
                vault.put("setupPending",text);
                sql.execSQL("UPDATE meta SET v=? WHERE k='node'",new Object[]{Integer.toString(target.node)});
                sql.execSQL("DELETE FROM meta WHERE k LIKE 'offlineAck%'");
                sql.setTransactionSuccessful();
            }finally{sql.endTransaction();}
        }else vault.put("setupPending",text);
        vault.put("peerConfig",text);vault.put("mode","p2p");vault.remove("setupPending");
    }
    private static String meta(SQLiteDatabase sql,String key){try(Cursor c=sql.rawQuery("SELECT v FROM meta WHERE k=?",new String[]{key})){return c.moveToFirst()?c.getString(0):"";}}
    private static boolean empty(SQLiteDatabase sql,String table){
        try(Cursor c=sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",new String[]{table})){if(!c.moveToFirst())return true;}
        try(Cursor c=sql.rawQuery("SELECT 1 FROM "+table+" LIMIT 1",null)){return !c.moveToFirst();}
    }
}
