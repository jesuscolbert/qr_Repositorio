package bo.soboce.comedor;

/** Ignore camera repeats while a QR remains visible; never queue scans behind a write. */
final class ScanGate {
    private String processed="";private long lastSeen;private boolean ready=true;
    boolean read(String qr,long now){
        boolean same=qr.equals(processed),returned=same&&now-lastSeen>1400;
        if(same)lastSeen=now;
        if(!ready||same&&!returned)return false;
        processed=qr;lastSeen=now;ready=false;return true;
    }
    void ready(){ready=true;}
    void hold(){ready=false;}
}
