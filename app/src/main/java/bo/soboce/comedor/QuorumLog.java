package bo.soboce.comedor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Fixed membership, crash-fault Paxos log. Storage MUST be durable before replies. */
public final class QuorumLog {
    public static final int MEMBERS = 4, MAJORITY = 3;
    public record Promise(boolean granted, long promised, long acceptedBallot, String value) {}
    public record Vote(int node, String signature) {}
    public record Decision(long slot, long ballot, String value, List<Vote> votes) {}
    public interface Storage {
        long nextBallot(int node, long greaterThan) throws Exception;
        long applied();
        void learn(Decision decision) throws Exception;
    }
    public interface Network {
        Promise prepare(int peer, long slot, long ballot) throws Exception;
        Vote accept(int peer, long slot, long ballot, String value) throws Exception;
        void learn(int peer, Decision decision) throws Exception;
    }
    private final int node;
    private final Storage storage;
    private final Network network;
    public QuorumLog(int node, Storage storage, Network network) {
        if (node < 0 || node >= MEMBERS) throw new IllegalArgumentException("node");
        this.node = node; this.storage = storage; this.network = network;
    }
    public synchronized Decision append(String command) throws Exception {
        // Catch up through chosen slots; never skip a slot after a timeout.
        for (int i = 0; i < 256; i++) {
            Decision decision = choose(storage.applied() + 1, command);
            storage.learn(decision);
            for (int peer = 0; peer < MEMBERS; peer++) {
                try { network.learn(peer, decision); } catch (Exception ignored) { /* anti-entropy retries */ }
            }
            if (decision.value().equals(command)) return decision;
        }
        throw new IOException("Sincronizando historial. Reintenta la misma operación.");
    }
    private Decision choose(long slot, String command) throws Exception {
        long floor = 0;
        for (int attempt = 0; attempt < 4; attempt++) {
            long ballot = storage.nextBallot(node, floor), highestAccepted = -1;
            String value = command;
            List<Integer> promises = new ArrayList<>();
            for (int peer = 0; peer < MEMBERS; peer++) {
                try {
                    Promise p = network.prepare(peer, slot, ballot);
                    floor = Math.max(floor, p.promised());
                    if (p.granted()) {
                        promises.add(peer);
                        if (p.value() != null && p.acceptedBallot() > highestAccepted) {
                            highestAccepted = p.acceptedBallot(); value = p.value();
                        }
                    }
                } catch (Exception ignored) { /* A timeout is never a vote. */ }
            }
            if (promises.size() < MAJORITY) throw new IOException("Se necesitan 3 de los 4 teléfonos conectados.");
            List<Vote> votes = new ArrayList<>();
            for (int peer : promises) {
                try {
                    Vote vote = network.accept(peer, slot, ballot, value);
                    if (vote != null && vote.node() == peer) votes.add(vote);
                } catch (Exception ignored) { /* Recover an uncertain choice in prepare. */ }
            }
            if (votes.size() >= MAJORITY) return new Decision(slot, ballot, value, votes);
            Thread.sleep(30L * (node + 1) * (attempt + 1));
        }
        throw new IOException("Otra terminal está registrando. Reintenta la misma operación.");
    }
}
