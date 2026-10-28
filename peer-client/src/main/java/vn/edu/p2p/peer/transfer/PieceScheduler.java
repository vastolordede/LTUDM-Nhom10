package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.FileSourceInfo;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S24-S25, S32-S33, B01-B02:
 * Bộ lập lịch phân phối piece và chọn Peer nguồn (hỗ trợ cả Round-Robin và Rarest-First).
 */
public final class PieceScheduler {

    public enum PieceState {
        PENDING,
        IN_FLIGHT,
        COMPLETED
    }

    public enum Strategy {
        ROUND_ROBIN,
        RAREST_FIRST,
        RANKED
    }

    public record Assignment(
            int pieceIndex,
            FileSourceInfo source
    ) {
    }

    private static final int DEFAULT_CORRUPT_STRIKE_LIMIT = 3;

    private final int pieceCount;
    private final Strategy strategy;
    private final int corruptStrikeLimit;

    private final PieceState[] states;
    private final Map<String, FileSourceInfo> activeSources = new HashMap<>();
    private final Map<String, BitSet> sourceBitfields = new HashMap<>();
    private final Map<String, Integer> corruptStrikes = new HashMap<>();
    private final Set<String> blacklistedPeers = new HashSet<>();
    private final List<String> sourceOrder = new ArrayList<>();
    private final AtomicInteger roundRobinCursor = new AtomicInteger(0);
    private final Map<Integer, String> assignedPeer = new HashMap<>();
    private final Map<Integer, Long> assignedAt = new HashMap<>();
    private final Map<String, Integer> inFlightByPeer = new HashMap<>();
    private final Map<String, Double> avgMillis = new HashMap<>();

    public PieceScheduler(int pieceCount, BitSet initialCompletedPieces, Strategy strategy) {
        this(pieceCount, initialCompletedPieces, strategy, DEFAULT_CORRUPT_STRIKE_LIMIT);
    }

    public PieceScheduler(
            int pieceCount,
            BitSet initialCompletedPieces,
            Strategy strategy,
            int corruptStrikeLimit
    ) {
        if (pieceCount < 0) {
            throw new IllegalArgumentException("pieceCount must be >= 0");
        }
        this.pieceCount = pieceCount;
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.corruptStrikeLimit = Math.max(1, corruptStrikeLimit);
        this.states = new PieceState[pieceCount];

        for (int i = 0; i < pieceCount; i++) {
            if (initialCompletedPieces != null && initialCompletedPieces.get(i)) {
                states[i] = PieceState.COMPLETED;
            } else {
                states[i] = PieceState.PENDING;
            }
        }
    }

    /** Đăng ký một Peer nguồn cùng BitSet các mảnh mà Peer đó đang sở hữu. */
    public synchronized void registerSource(FileSourceInfo source, BitSet bitfield) {
        Objects.requireNonNull(source, "source");
        if (blacklistedPeers.contains(source.peerId())) {
            return;
        }
        BitSet copy = bitfield == null ? new BitSet(pieceCount) : (BitSet) bitfield.clone();
        activeSources.put(source.peerId(), source);
        sourceBitfields.put(source.peerId(), copy);
        if (!sourceOrder.contains(source.peerId())) {
            sourceOrder.add(source.peerId());
        }
    }

    /**
     * S24-S25 & B01: Cấp phát mảnh tiếp theo cần tải cùng Peer nguồn phù hợp.
     */
    public synchronized Optional<Assignment> nextAssignment() {
        if (activeSources.isEmpty() || isAllCompleted()) {
            return Optional.empty();
        }

        int chosenPiece = (strategy == Strategy.RAREST_FIRST || strategy == Strategy.RANKED)
                ? selectRarestPendingPiece()
                : selectSequentialPendingPiece();

        if (chosenPiece < 0) {
            return Optional.empty();
        }

        FileSourceInfo chosenSource = (strategy == Strategy.RANKED)
                ? selectRankedSourceForPiece(chosenPiece)
                : selectRoundRobinSourceForPiece(chosenPiece);
        if (chosenSource == null) {
            return Optional.empty();
        }

        states[chosenPiece] = PieceState.IN_FLIGHT;
        assignedPeer.put(chosenPiece, chosenSource.peerId());
        assignedAt.put(chosenPiece, System.nanoTime());
        inFlightByPeer.merge(chosenSource.peerId(), 1, Integer::sum);
        return Optional.of(new Assignment(chosenPiece, chosenSource));
    }

    private int selectSequentialPendingPiece() {
        for (int i = 0; i < pieceCount; i++) {
            if (states[i] == PieceState.PENDING && hasAnySourceForPiece(i)) {
                return i;
            }
        }
        return -1;
    }

    /** B01: Rarest-First Scheduler — ưu tiên mảnh có ít Peer nguồn nắm giữ nhất. */
    private int selectRarestPendingPiece() {
        int bestPiece = -1;
        int minAvailability = Integer.MAX_VALUE;

        for (int i = 0; i < pieceCount; i++) {
            if (states[i] != PieceState.PENDING) {
                continue;
            }
            int availability = countSourcesHavingPiece(i);
            if (availability > 0 && availability < minAvailability) {
                minAvailability = availability;
                bestPiece = i;
            }
        }
        return bestPiece;
    }

    private int countSourcesHavingPiece(int pieceIndex) {
        int count = 0;
        for (String peerId : sourceOrder) {
            BitSet bits = sourceBitfields.get(peerId);
            if (bits != null && bits.get(pieceIndex)) {
                count++;
            }
        }
        return count;
    }

    private boolean hasAnySourceForPiece(int pieceIndex) {
        return countSourcesHavingPiece(pieceIndex) > 0;
    }

    /** S25: Chọn Peer nguồn theo vòng tròn Round-Robin trong số các Peer có mảnh pieceIndex. */
    private FileSourceInfo selectRoundRobinSourceForPiece(int pieceIndex) {
        int size = sourceOrder.size();
        if (size == 0) {
            return null;
        }
        int start = Math.floorMod(roundRobinCursor.getAndIncrement(), size);
        for (int offset = 0; offset < size; offset++) {
            String peerId = sourceOrder.get((start + offset) % size);
            BitSet bits = sourceBitfields.get(peerId);
            if (bits != null && bits.get(pieceIndex)) {
                return activeSources.get(peerId);
            }
        }
        return null;
    }

    public synchronized void markPieceCompleted(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < pieceCount) {
            states[pieceIndex] = PieceState.COMPLETED;
            releaseAssignment(pieceIndex, true);
        }
    }

    /**
     * S33: Đưa piece chưa hoàn thành về trạng thái PENDING để luồng/nguồn khác tải lại.
     */
    public synchronized void releasePieceToPending(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < pieceCount && states[pieceIndex] != PieceState.COMPLETED) {
            states[pieceIndex] = PieceState.PENDING;
            releaseAssignment(pieceIndex, false);
        }
    }

    /**
     * S32: Xử lý khi một Peer nguồn bị ngắt kết nối giữa chừng.
     */
    public synchronized void markSourceDisconnected(String peerId) {
        activeSources.remove(peerId);
        sourceBitfields.remove(peerId);
        sourceOrder.remove(peerId);
    }

    /**
     * B02: Ghi nhận một Peer gửi piece bị sai SHA-256; nếu vượt ngưỡng thì đưa vào Blacklist.
     */
    public synchronized boolean recordCorruptPieceFromPeer(String peerId) {
        int strikes = corruptStrikes.merge(peerId, 1, Integer::sum);
        if (strikes >= corruptStrikeLimit) {
            blacklistedPeers.add(peerId);
            markSourceDisconnected(peerId);
            return true;
        }
        return false;
    }

    public synchronized boolean isPeerBlacklisted(String peerId) {
        return blacklistedPeers.contains(peerId);
    }

    public synchronized int completedCount() {
        int count = 0;
        for (PieceState state : states) {
            if (state == PieceState.COMPLETED) {
                count++;
            }
        }
        return count;
    }

    public synchronized int inFlightCount() {
        int count = 0;
        for (PieceState state : states) {
            if (state == PieceState.IN_FLIGHT) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean isAllCompleted() {
        return completedCount() == pieceCount;
    }

    public synchronized int activeSourceCount() {
        return activeSources.size();
    }

    public synchronized boolean hasRemainingWork() {
        if (isAllCompleted()) {
            return false;
        }
        if (inFlightCount() > 0) {
            return true;
        }
        for (int i = 0; i < pieceCount; i++) {
            if (states[i] == PieceState.PENDING && hasAnySourceForPiece(i)) {
                return true;
            }
        }
        return false;
    }

    /** B04: chọn nguồn có chi phí kỳ vọng thấp nhất (độ trễ x tải x hệ số phạt piece lỗi). */
    private FileSourceInfo selectRankedSourceForPiece(int pieceIndex) {
        int size = sourceOrder.size();
        if (size == 0) {
            return null;
        }
        int start = Math.floorMod(roundRobinCursor.getAndIncrement(), size);
        String best = null;
        double bestCost = Double.MAX_VALUE;
        for (int offset = 0; offset < size; offset++) {
            String peerId = sourceOrder.get((start + offset) % size);
            BitSet bits = sourceBitfields.get(peerId);
            if (bits == null || !bits.get(pieceIndex)) {
                continue;
            }
            double latency = avgMillis.getOrDefault(peerId, 0.0);
            int load = inFlightByPeer.getOrDefault(peerId, 0);
            int strikes = corruptStrikes.getOrDefault(peerId, 0);
            double cost = (latency + 1.0) * (load + 1) * (1 + strikes);
            if (cost < bestCost) {
                bestCost = cost;
                best = peerId;
            }
        }
        return best == null ? null : activeSources.get(best);
    }

    private void releaseAssignment(int pieceIndex, boolean success) {
        String peerId = assignedPeer.remove(pieceIndex);
        Long startedAt = assignedAt.remove(pieceIndex);
        if (peerId == null) {
            return;
        }
        inFlightByPeer.merge(peerId, -1, Integer::sum);
        if (success && startedAt != null) {
            double ms = (System.nanoTime() - startedAt) / 1_000_000.0;
            avgMillis.merge(peerId, ms, (old, cur) -> 0.7 * old + 0.3 * cur);
        }
    }
}