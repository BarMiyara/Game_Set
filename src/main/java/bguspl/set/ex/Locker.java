package bguspl.set.ex;

public interface Locker {
    boolean playerLocked(int playerId);
    boolean cellLocked(int cell);
}
