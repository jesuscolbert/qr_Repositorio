package bo.soboce.comedor;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class PeerConfig {
    final JSONObject raw, manifest, seed;
    final JSONArray nodes;
    final int node;
    final String cluster, secret;
    PeerConfig(String text) throws Exception {
        raw = new JSONObject(text);
        require(raw.getString("format").equals("SOBOCE-P2P-1"), "Archivo de red incompatible.");
        manifest = new JSONObject(raw.getString("manifest"));
        seed = new JSONObject(raw.getString("seed"));
        cluster = hash(raw.getString("manifest"));
        require(hash(raw.getString("seed")).equals(manifest.getString("seedHash")), "Padrón alterado.");
        secret = raw.getString("secret"); node = raw.getInt("node"); nodes = manifest.getJSONArray("nodes");
        require(secret.matches("[a-f0-9]{64}") && node >= 0 && node < 4 && nodes.length() == 4, "Red inválida.");
        java.util.Set<String> urls = new java.util.HashSet<>();
        for(int i=0;i<4;i++) {
            JSONObject n=nodes.getJSONObject(i);
            String url=n.getString("url");
            require(url.matches("https://[a-z0-9][a-z0-9.-]+[a-z0-9]") && urls.add(url), "Cada teléfono requiere una dirección HTTPS distinta.");
            require(n.getInt("id")==i && n.getString("name").length()>=3, "Identidad inválida.");
            require(n.getString("role").matches("admin|encargado"), "Rol inválido.");
            require(seed.getJSONArray("plants").toString().contains(JSONObject.quote(n.getString("plant"))), "Planta inválida.");
        }
        JSONObject tunnel=raw.getJSONObject("tunnel");
        require(tunnel.getString("TunnelID").matches("[a-f0-9-]{36}") && !tunnel.getString("TunnelSecret").isEmpty(), "Falta la credencial de túnel de este teléfono.");
        require(!tunnel.getString("AccountTag").isEmpty(), "Falta la cuenta del túnel.");
    }
    String mac(String value) throws Exception {
        Mac m=Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return hex(m.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
    String vote(int node,long slot,long ballot,String value) throws Exception {
        return mac("vote|"+cluster+"|"+node+"|"+slot+"|"+ballot+"|"+hash(value));
    }
    static boolean equal(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    static String hash(String value) throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return hex(b);}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
