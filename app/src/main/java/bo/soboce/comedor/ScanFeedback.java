package bo.soboce.comedor;

import org.json.JSONObject;

final class ScanFeedback {
    enum Kind { ALLOWED, DUPLICATE, DENIED, PENDING }
    static Kind kind(JSONObject reply){
        if(reply.optBoolean("ok")&&reply.optString("status").equals("accepted"))return Kind.ALLOWED;
        if(reply.optBoolean("ok")&&(reply.optString("status").equals("duplicate")||reply.optString("status").equals("recovered")))return Kind.DUPLICATE;
        if(!reply.optBoolean("ok")&&reply.optBoolean("definitive"))return Kind.DENIED;
        return Kind.PENDING;
    }
    static String title(Kind kind){switch(kind){case ALLOWED:return "ACCESO PERMITIDO";case DUPLICATE:return "YA REGISTRADO";case DENIED:return "ACCESO DENEGADO";default:return "SIN CONFIRMACIÓN";}}
    static long delay(Kind kind){return kind==Kind.ALLOWED?1700:kind==Kind.PENDING?0:3200;}
}
