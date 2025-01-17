package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private Locker managerOfTable;
    private Tokens tokens;
    private BlockingQueue<Integer> pressedQueue;
    private BlockingQueue<Integer> popQueue;
    private volatile boolean isWait;
    private volatile boolean notFullTokens;
    private boolean isFrozen;
    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        tokens = new Tokens(env.config.featureSize,id);
        pressedQueue = new LinkedBlockingQueue<>(env.config.featureSize);
        popQueue = new LinkedBlockingQueue<>(1);
        isWait = false;
        notFullTokens = true;

    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            while((!tokens.full()||tokens.needToRemove())&&!tokens.terminate()) {
                try {waitKeyPress();} catch (InterruptedException e) {throw new RuntimeException(e);}
        }
            if(!tokens.terminate()){
                notFullTokens = false;
                try{table.needCheckSet(tokens);} catch (InterruptedException e) {throw new RuntimeException(e);}
                try{checkPopQueue();} catch (InterruptedException ex){throw new RuntimeException(ex);}
                notFullTokens = true;
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        table.turnPlayerOff(id);
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        Random rand = new Random();
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                keyPressed(rand.nextInt(env.config.tableSize));
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        tokens.turnOff();
        consume();
        try{pressedQueue.put(-1);} catch (InterruptedException e) {e.printStackTrace();};
        try{putActionInPOPQ(-1);} catch (InterruptedException e) {e.printStackTrace();};
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if((!tokens.full()|| (tokens.needToRemove()&&tokens.containSlot(slot))) && (!managerOfTable.cellLocked(slot))&&(!managerOfTable.playerLocked(id)) && !isFrozen){
            if(!tokens.terminate()){
                try {pressedQueue.put(slot);} catch (InterruptedException e){throw new RuntimeException(e);}
            }
        }
    }
    private void waitKeyPress() throws InterruptedException{
            int slot = pressedQueue.take();
            if(slot!= -1){
                if(tokens.putOrTakeToken(slot)){table.placeToken(id,slot);}
                else {table.removeToken(id,slot);}
            }
        }

    public void putActionInPOPQ(int action) throws InterruptedException {
        try{popQueue.put(action);} catch (InterruptedException e){throw new InterruptedException();}
    }
    private void checkPopQueue() throws InterruptedException {
        if(!terminate){
            int act = popQueue.take();
            isWait=true;
            if (act == -1) penalty();
            else if(act == 1) point();
            else {
                tokens.setNeedToRemove(true);
                for (int slot :tokens.getSlots()){

                        tokens.putOrTakeToken(slot);
                        table.removeToken(id,slot);
                    }
                }
                isWait=false;
            }
        }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        for(int slot: tokens.getSlots()) table.removeToken(id,slot);
        tokens.clear();
        if(env.config.pointFreezeMillis>0){
            freeze(env.config.pointFreezeMillis);}
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        tokens.setNeedToRemove(true);
        if(env.config.penaltyFreezeMillis>0){
            freeze(env.config.penaltyFreezeMillis);
        }
    }
    private void freeze(long freezing_time){
        isFrozen = true;
        while (freezing_time>0){
            env.ui.setFreeze(id,freezing_time);
            try {Thread.sleep(1);}catch (InterruptedException e){throw new RuntimeException(e);}
            freezing_time-=1;
        }
        env.ui.setFreeze(id,0);
        isFrozen = false;
    }

    public int score() {return score;}
    public void setLocker(Locker dealer){managerOfTable = dealer;}
    public void reset(){tokens.clear();}
    public void removeTokFromSlot(int slot) {
        if (!tokens.containSlot(slot))
            tokens.putOrTakeToken(slot);
        tokens.putOrTakeToken(slot);
        table.removeToken(id,slot);
    }
    public boolean notFullTokens(){return notFullTokens;}
    private void consume(){
        while (!pressedQueue.isEmpty()){
            try {pressedQueue.take();} catch (InterruptedException e) {e.printStackTrace();}
        }
    }
}
