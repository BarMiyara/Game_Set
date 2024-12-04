package bguspl.set.ex;

import bguspl.set.Env;

import javax.swing.*;
import javax.swing.Timer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable,Locker {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;


    Timer timerSeconds;
    Timer timerMiliSeconds;
    private boolean [] playersLocked;
    private boolean [] slotsLocked;
    private int timeNow;
    boolean isBusy;
    Random random;
    boolean needReshuffle;
    boolean warningTime;
    Vector<Integer> turnOff;
    private int [] cardsToRemove;




    private enum ModeOfGame{countDown, countUp, noCount}
    ModeOfGame modeOfGame;
    Thread [] playerThreads;




    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        playersLocked = new boolean[env.config.players];
        slotsLocked = new boolean[env.config.tableSize];
        Arrays.fill(playersLocked,true);
        Arrays.fill(slotsLocked,true);
        isBusy = true;
        random = new Random();
        playerThreads = new Thread[env.config.players];
        needReshuffle = false;

        // init the timer
        timerSeconds = new Timer (1000, e -> {
            timeNow+= 1000;
            updateTimerDisplay(false);
            if(reshuffleTime - timeNow <= env.config.turnTimeoutWarningMillis){
                warningTime = true;
                timerSeconds.stop();
                timerMiliSeconds.start();

            }



        } );
        timerMiliSeconds = new Timer (10, e -> {
            timeNow += 10;
            updateTimerDisplay(false);
        });

        // Bonus what happened when the timer init from 0 or less
        // mode of game
        switch ((int) env.config.turnTimeoutMillis){
            case(0):
            {   modeOfGame = ModeOfGame.countUp;
                reshuffleTime = Long.MAX_VALUE;
                break;
            }
            case(-1): {
                modeOfGame = ModeOfGame.noCount;
                reshuffleTime = Long.MAX_VALUE;
                break;
            }
            default:
            {
                modeOfGame = ModeOfGame.countDown;
                reshuffleTime = env.config.turnTimeoutMillis;
            }

        }
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (int i = 0; i < env.config.players; i++) {
            players[i].setLocker(this);
            playerThreads[i] = new Thread (players[i], "player" + players[i].id+"Thread");
            playerThreads[i].start();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();

            for(Player player : players) {player.reset();}
            needReshuffle = false;
            timeNow = 0;
            warningTime = false;
            timerSeconds.start();
            updateTimerDisplay(false);
            unLockAllPlayers();
            timerLoop();
            removeAllCardsFromTable();
        }
        if(!terminate||modeOfGame!=ModeOfGame.countDown){
            announceWinners();
            isBusy = false;
            for(int i = env.config.players -1; i >=0; i--) {
                players[i].terminate();
            }
        }
        turnOff = new Vector<>();
        while(!playersOff()){
            try{turnOff.add(table.turnOffTable.take());}catch(InterruptedException e){e.printStackTrace();}

        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    private boolean playersOff(){return turnOff.size() == env.config.players;}

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while ((!terminate && timeNow < reshuffleTime)&& !needReshuffle) {
            sleepUntilWokenOrTimeout();
            placeCardsOnTable();
            unLockAllPlayers();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        while(getIsBusy()){
            try {Thread.sleep(50);} catch (InterruptedException ex) {ex.printStackTrace();}}
        for(int i = env.config.players-1; i >= 0; i--){
            players[i].terminate();
        }
        terminate = true;
        //checking
        try {table.setsOnTable.put(new Tokens());} catch (InterruptedException ex2){ex2.printStackTrace();}
    }
    private boolean getIsBusy(){return isBusy;}
    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        isBusy = true;
        for(int cell:cardsToRemove){
            lockSlot(cell);
            for(Player player : players) {
                if(player.notFullTokens())player.removeTokFromSlot(cell);}
            table.removeCard(cell);
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        int[] cellsToPlace = new int[env.config.tableSize - table.countCards()];
        int c =0;
        for(int i=0;i<env.config.tableSize;i++){
            if(table.slotToCard[i]==null){
                cellsToPlace[c]=i;
                c++;
            }
        }
        shuffleSlots(cellsToPlace);
        for(int cell : cellsToPlace){
            if(!deck.isEmpty()){
                int card = deck.remove(random.nextInt(deck.size()));
                table.placeCard(card,cell);
                unLockSlot(cell);

            }
        }
//        for(int cell : cellsToPlace){unLockSlot(cell);}
        if(modeOfGame != ModeOfGame.countDown){
            if(!shouldFinish()){
                List<Integer> checkSetOnTable = new ArrayList<>(Arrays.asList(table.slotToCard));
                List<int[]> sets = env.util.findSets(checkSetOnTable,1);
                if(sets.size()==0)needReshuffle = true;


            }else {
                terminate = true;
                timerSeconds.stop();
            }
        }
        if (env.config.hints)table.hints();
        isBusy = false;
    }


    private void shuffleSlots(int[] slots)
    {
        for (int i = slots.length - 1; i > 0; i--) {
            int index = random.nextInt(i + 1);
            // Swap arr[i] and arr[index]
            int temp = slots[i];
            slots[i] = slots[index];
            slots[index] = temp;
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {

        try{Tokens set = table.setsOnTable.take();
            if(!set.isFlag()){
                lockPlayer(set.getPlayerId());
                testLegalSet(set);
            }
        }catch(InterruptedException e){e.printStackTrace();}


    }
    private boolean checkPossibleToTest(Tokens cardsForTest){
        int [] cardsOnSlots = cardsForTest.getCardsForTest();
        int[] numOfSLots = cardsForTest.getSlots();
        for(int i =0; i<cardsOnSlots.length;i++){
            if(table.slotToCard[numOfSLots[i]]==null|| table.slotToCard[numOfSLots[i]]!=cardsOnSlots[i] ){
                return false;
            }
        }return true;
    }
    public void testLegalSet(Tokens set) throws InterruptedException {
        if(set.withProblem()) players[set.getPlayerId()].putActionInPOPQ(0);
        else{
            Player player = players[set.getPlayerId()];
            if(checkPossibleToTest(set)){
                int [] currSet = set.getCardsForTest();
                int [] currSlots = set.getSlots();


                //checking if legal
                if(env.util.testSet(currSet)){
                    //point
                    player.putActionInPOPQ(1);
                    cardsToRemove = currSlots;
                    removeCardsFromTable();
                    timeNow = 0;
                    timerMiliSeconds.stop();
                    timerSeconds.start();
                } else {
                    // penalty
                    player.putActionInPOPQ(-1);
                }
            }else{
                player.putActionInPOPQ(0);
            }}}

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private synchronized void updateTimerDisplay(boolean reset) {
        if(modeOfGame == ModeOfGame.countDown){
            long cd = reshuffleTime - timeNow;
            env.ui.setCountdown(cd,warningTime);
            if(cd == 0){
                timerMiliSeconds.stop();
                try{table.setsOnTable.put(new Tokens());}catch(InterruptedException ex){throw new RuntimeException(ex);}
            }
        }else if(modeOfGame == ModeOfGame.countUp){
            env.ui.setElapsed(timeNow);
        }



    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        isBusy=true;
        int[] cellsFull= new int[table.countCards()];
        int c=0;
        for (int i=0;i<env.config.tableSize;i++){
            if (table.slotToCard[i]!=null){
                cellsFull[c] = i;
                c++;
            }
        }
        shuffleSlots(cellsFull);
        for (int slot: cellsFull){
            lockSlot(slot);
            for(int i=0;i<env.config.players;i++){
                players[i].removeTokFromSlot(slot);
            }
            deck.add(table.removeCard(slot));
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        ArrayList<Integer> winners = new ArrayList<>();
        int maxScore = 0;
        int playerId = -1;
        for (Player player : players) {
            if (player.score() >= maxScore) {
                maxScore = player.score();
                playerId = player.id;}
        }
        winners.add(playerId);
        for (Player player : players) {
            if (player.id != playerId && player.score() == maxScore) winners.add(player.id);
        }
        int[] winnersId = new int[winners.size()];
        for (int i = 0; i < winners.size(); i++) winnersId[i] = winners.get(i);
        env.ui.announceWinner(winnersId);
    }

    @Override
    public boolean playerLocked(int playerId) {return playersLocked[playerId];}
    @Override
    public boolean cellLocked(int cell) {return slotsLocked[cell];}
    private void lockSlot(int cell){slotsLocked[cell]=true;}
    private void unLockSlot(int cell) {slotsLocked[cell] = false;}
    private void unLockAllPlayers() {Arrays.fill(playersLocked, false);}
    private void lockPlayer(int playerId) {playersLocked[playerId]=true;}
}
