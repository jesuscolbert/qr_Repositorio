package bo.soboce.comedor;



import org.json.*;

import java.nio.charset.StandardCharsets;

import java.security.MessageDigest;

import java.security.SecureRandom;

import java.security.*;

import java.security.spec.*;

import java.util.Base64;



final class PeerConfig {

    final JSONObject raw, manifest, seed;

    final JSONArray nodes;

    final int node;

    final String cluster;

    final PrivateKey identity;

    PeerConfig(String text) throws Exception {

        raw = new JSONObject(text);

        require(raw.getString("format").equals("SOBOCE-P2P-2"), "Archivo de red incompatible.");

        manifest = new JSONObject(raw.getString("manifest"));

        seed = new JSONObject(raw.getString("seed"));

        cluster = hash(raw.getString("manifest"));

        require(hash(raw.getString("seed")).equals(manifest.getString("seedHash")), "Padrón alterado.");

        node = raw.getInt("node"); nodes = manifest.getJSONArray("nodes");

        identity=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(raw.getString("identityPrivate"))));

        require(node >= 0 && node < 4 && nodes.length() == 4, "Red inválida.");

        java.util.Set<String> urls = new java.util.HashSet<>();

        for(int i=0;i<4;i++) {

            JSONObject n=nodes.getJSONObject(i);

            String url=n.getString("url");

            require(url.matches("https://[a-z0-9][a-z0-9.-]+[a-z0-9]") && urls.add(url), "Cada teléfono requiere una dirección HTTPS distinta.");

            require(n.getInt("id")==i && n.getString("name").length()>=3, "Identidad inválida.");

            require(n.getString("role").matches("admin|encargado"), "Rol inválido.");

            require(seed.getJSONArray("plants").toString().contains(JSONObject.quote(n.getString("plant"))), "Planta inválida.");

        }

        require(verify(node,"enrolment|"+cluster,sign("enrolment|"+cluster)),"La clave no corresponde a este teléfono.");

        JSONObject tunnel=raw.getJSONObject("tunnel");

        require(tunnel.getString("TunnelID").matches("[a-f0-9-]{36}") && !tunnel.getString("TunnelSecret").isEmpty(), "Falta la credencial de túnel de este teléfono.");

        require(!tunnel.getString("AccountTag").isEmpty(), "Falta la cuenta del túnel.");

    }

    String sign(String value) throws Exception {

        Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(identity);s.update(value.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(s.sign());

    }

    boolean verify(int peer,String value,String signature) throws Exception {

        if(peer<0||peer>=4)return false;

        PublicKey key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(nodes.getJSONObject(peer).getString("publicKey"))));

        Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(key);s.update(value.getBytes(StandardCharsets.UTF_8));return s.verify(Base64.getDecoder().decode(signature));

    }

    String vote(int node,long slot,long ballot,String value) throws Exception {

        require(node==this.node,"No se puede votar por otro equipo.");return sign(voteText(node,slot,ballot,value));

    }

    boolean validVote(int peer,long slot,long ballot,String value,String signature) throws Exception {return verify(peer,voteText(peer,slot,ballot,value),signature);}

    String voteText(int peer,long slot,long ballot,String value) throws Exception {return "vote|"+cluster+"|"+peer+"|"+slot+"|"+ballot+"|"+hash(value);}

    String seal(JSONObject command) throws Exception {

        require(command.getInt("actor")==node,"Origen inválido.");String body=command.toString();return new JSONObject().put("body",body).put("signature",sign("command|"+cluster+"|"+body)).toString();

    }

    JSONObject command(String value) throws Exception {

        JSONObject sealed=new JSONObject(value);String body=sealed.getString("body");JSONObject command=new JSONObject(body);

        require(verify(command.getInt("actor"),"command|"+cluster+"|"+body,sealed.getString("signature")),"Operación sin firma del encargado.");return command;

    }

    static boolean equal(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}

    static String hash(String value) throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}

    static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return hex(b);}

    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}

    static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}

}

