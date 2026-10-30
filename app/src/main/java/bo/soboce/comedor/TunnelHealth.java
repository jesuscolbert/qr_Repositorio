package bo.soboce.comedor;

import java.util.*;
import java.util.regex.*;

/** Translate connector output to a small allowlist of messages; never expose raw log lines. */
final class TunnelHealth {
    private final Set<Integer> connections=new HashSet<>();
    private String problem="Esperando conexión con Cloudflare…";
    synchronized void reset(){connections.clear();problem="Esperando conexión con Cloudflare…";}
    synchronized void line(String text){
        Matcher m=Pattern.compile("connIndex=(\\d+)").matcher(text);int index=m.find()?Integer.parseInt(m.group(1)):-1;
        String lower=text.toLowerCase(Locale.ROOT);
        if(text.contains("Registered tunnel connection")&&index>=0){connections.add(index);problem="";}
        else if((text.contains("Unregistered tunnel connection")||text.contains("Connection terminated"))&&index>=0)connections.remove(index);
        else if(lower.contains("unauthorized")||lower.contains("invalid tunnel secret"))problem="Cloudflare rechazó el acceso del túnel. Revisa el instalador asignado.";
        else if(lower.contains("lookup")||lower.contains("no such host"))problem="No se pudo resolver la dirección de Cloudflare. Revisa la conexión de internet.";
        else if(lower.contains("timeout")||lower.contains("network is unreachable")||lower.contains("connection refused"))problem="La red no permite conectar el túnel. Prueba datos móviles u otra red.";
    }
    synchronized void exited(int code){connections.clear();problem="El conector se detuvo (código "+code+"). Se intentará reconectar.";}
    synchronized String message(){return connections.isEmpty()?problem.isEmpty()?"Reconectando con Cloudflare…":problem:"Túnel conectado · "+connections.size()+" conexiones con Cloudflare";}
}
