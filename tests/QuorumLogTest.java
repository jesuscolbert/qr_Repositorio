package bo.soboce.comedor;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Runs on the JVM; exercises the same proposer shipped in the APK. */
public final class QuorumLogTest {
    static class Disk implements QuorumLog.Storage {
        long counter, applied;
        final Map<Long, QuorumLog.Promise> states = new HashMap<>();
        final Map<Long, QuorumLog.Decision> log = new TreeMap<>();
        public synchronized long nextBallot(int n, long floor) {
            counter = Math.max(counter + 1, floor / 4 + 1); return counter * 4 + n;
        }
        public synchronized long applied() { return applied; }
        synchronized QuorumLog.Promise prepare(long slot, long ballot) {
            var p = states.getOrDefault(slot, new QuorumLog.Promise(true, 0, 0, null));
            if (ballot < p.promised()) return new QuorumLog.Promise(false, p.promised(), p.acceptedBallot(), p.value());
            var result = new QuorumLog.Promise(true, ballot, p.acceptedBallot(), p.value());
            states.put(slot, result); return result;
        }
        synchronized QuorumLog.Vote accept(int n, long slot, long ballot, String value) {
            var p = states.getOrDefault(slot, new QuorumLog.Promise(true, 0, 0, null));
            if (ballot < p.promised() || (p.acceptedBallot() == ballot && p.value() != null && !p.value().equals(value))) return null;
            states.put(slot, new QuorumLog.Promise(true, ballot, ballot, value));
            return new QuorumLog.Vote(n, "test");
        }
        public synchronized void learn(QuorumLog.Decision d) {
            var old = log.get(d.slot());
            check(old == null || old.value().equals(d.value()), "Conflicting committed values");
            log.put(d.slot(), d);
            while (log.containsKey(applied + 1)) applied++;
        }
    }
    static class Cluster {
        final Disk[] disks = { new Disk(), new Disk(), new Disk(), new Disk() };
        final boolean[][] reachable = new boolean[4][4];
        boolean loseLearn;
        Cluster() { for (var row : reachable) Arrays.fill(row, true); }
        QuorumLog node(int from) {
            return new QuorumLog(from, disks[from], new QuorumLog.Network() {
                void available(int p) throws IOException { if (!reachable[from][p]) throw new IOException("offline"); }
                public QuorumLog.Promise prepare(int p, long s, long b) throws Exception { available(p); return disks[p].prepare(s,b); }
                public QuorumLog.Vote accept(int p,long s,long b,String v) throws Exception { available(p); return disks[p].accept(p,s,b,v); }
                public void learn(int p,QuorumLog.Decision d) throws Exception { available(p); if (loseLearn) throw new IOException("lost response"); disks[p].learn(d); }
            });
        }
    }
    static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    static void unavailable(QuorumLog q) throws Exception {
        try { q.append("forbidden"); throw new AssertionError("Minority committed"); } catch (IOException expected) {}
    }
    public static void main(String[] args) throws Exception {
        Cluster all = new Cluster(); all.node(0).append("meal-A");
        for (Disk d : all.disks) check(d.applied() == 1, "Replication");
        Cluster oneOffline = new Cluster(); for (int i=0;i<4;i++) oneOffline.reachable[i][3]=false;
        oneOffline.node(0).append("meal-A"); check(oneOffline.disks[0].applied()==1, "Three must work");
        Cluster split = new Cluster(); for (int i=0;i<4;i++) for(int j=0;j<4;j++) split.reachable[i][j]=(i/2==j/2);
        unavailable(split.node(0)); unavailable(split.node(2));
        Cluster recovery = new Cluster();
        for(int i=0;i<3;i++){recovery.disks[i].prepare(1,4);recovery.disks[i].accept(i,1,4,"chosen-before-crash");}
        recovery.node(3).append("next");
        check(recovery.disks[3].log.get(1L).value().equals("chosen-before-crash"), "Lost decision must survive new proposer");
        check(recovery.disks[3].log.get(2L).value().equals("next"), "Next slot");
        Cluster minority = new Cluster(); minority.disks[0].prepare(1,4);
        minority.disks[0].accept(0,1,4,"partially-accepted"); minority.node(1).append("new");
        check(minority.disks[1].log.get(1L).value().equals("partially-accepted"), "Highest acceptance adopted");
        Cluster race = new Cluster(); ExecutorService threads=Executors.newFixedThreadPool(4);
        List<Future<?>> futures=new ArrayList<>();
        for(int n=0;n<4;n++){final int id=n;futures.add(threads.submit(()->{try{race.node(id).append("scan-"+id);}catch(Exception retryable){/* safety checked below */}}));}
        for(var f:futures)f.get();threads.shutdown();
        Map<Long,String> chosen=new HashMap<>();
        for(var disk:race.disks)for(var entry:disk.log.entrySet()){
            var prior=chosen.putIfAbsent(entry.getKey(),entry.getValue().value());
            check(prior==null||prior.equals(entry.getValue().value()),"Race forked history");
        }
        // A process restart preserves promises and the monotonically increasing ballot counter.
        long previous = recovery.disks[3].counter; recovery.node(3).append("after-restart");
        check(recovery.disks[3].counter>previous,"Ballot reuse after restart");
        Disk ordered=new Disk();ordered.learn(new QuorumLog.Decision(2,4,"later",List.of()));
        check(ordered.applied()==0,"Gap applied");ordered.learn(new QuorumLog.Decision(1,4,"first",List.of()));
        check(ordered.applied()==2,"Gap not drained");
        System.out.println("PASS: replication, 3/4, 2+2 partition, lost decision, adoption, racing proposers, restart, ordered catch-up");
    }
}
