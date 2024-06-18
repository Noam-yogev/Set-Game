package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.LinkedBlockingDeque;

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
    protected Thread playerThread;

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
    /**
     * Our field - queue
     */
    private LinkedBlockingDeque<Integer> actionsQ;
    private final Dealer dealer;
    private volatile long sleepingTime;

    protected LinkedList<Boolean> isItSet;
    protected boolean isSleeping;

    protected int numTokens;
    protected boolean removedFromQ;


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
        this.actionsQ = new LinkedBlockingDeque<Integer>(env.config.featureSize);
        this.dealer = dealer;
        this.sleepingTime = 0;
        this.isItSet = new LinkedList<Boolean>();
        this.isSleeping = false;
        this.numTokens = 0;
        this.removedFromQ = false;
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
            // TODO implement main player loop
                sleepingTime = 0;
                if(!actionsQ.isEmpty() && !dealer.stopPlayers) {
                    int pressedCard = actionsQ.remove();
                            if (table.cardToSlot[pressedCard] != null && table.tokensPlayers[id][table.cardToSlot[pressedCard]] == 0 && table.tokensCountPerPlayer[id] < env.config.featureSize) {
                                synchronized (table.cardToSlot[pressedCard]) {
                                    if(table.cardToSlot[pressedCard] != null) {
                                        table.placeToken(id, table.cardToSlot[pressedCard]);
                                    }
                                }
                                if (table.tokensCountPerPlayer[id] == env.config.featureSize) {
                                    synchronized (dealer)
                                    {
                                        if(!dealer.stopPlayers)
                                            dealer.declaredSets.add(id);
                                    }
                                    synchronized (isItSet) {
                                        while (isItSet.isEmpty() && !removedFromQ) {
                                            try {
                                                isItSet.wait();
                                            } catch (InterruptedException ignored) {
                                            }
                                        }
                                    }
                                    if(!isItSet.isEmpty()){
                                        if (isItSet.get(0) == true) {
                                            point();
                                        } else {
                                            penalty();
                                        }
                                        isItSet.remove(0);
                                    }
                                    removedFromQ=false;
                                }
                            } else if (table.cardToSlot[pressedCard] != null && (table.tokensPlayers[id][table.cardToSlot[pressedCard]]) == 1) {
                                synchronized (table.cardToSlot[pressedCard]) {
                                    if(table.cardToSlot[pressedCard] != null) {
                                        table.removeToken(id, table.cardToSlot[pressedCard]);
                                    }
                                }
                            }
                        }
                }
        if (!human) try {
            aiThread.join();
        }
        catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random rand = new Random();

            while (!terminate) {
                // TODO implement player key press simulator
                Player.this.keyPressed(rand.nextInt(env.config.tableSize));
//                try {
//                    synchronized (this) { wait(); }
//                } catch (InterruptedException ignored) {}
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
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
            if (!isSleeping && !dealer.stopPlayers) {
                if(table.slotToCard[slot]!=null){
                    try {
                        actionsQ.put(table.slotToCard[slot]);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
    }


    @Override
    public String toString() {
        return super.toString();
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement
        sleepingTime = env.config.pointFreezeMillis;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        long secPoint = sleepingTime/1000;
        for(long i=secPoint ; i>=0 ; i= i-1){
            env.ui.setFreeze(id , i*1000);
            try{
                long oneSec = 1000;
                isSleeping = true;
                playerThread.sleep(oneSec);
                isSleeping = false;
            } catch (InterruptedException e){}
        }
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        sleepingTime = env.config.penaltyFreezeMillis;
        long secPenalty = sleepingTime/1000;
        for(long i=secPenalty ; i>=0 ; i= i-1){
            env.ui.setFreeze(id , i*1000);
            try{
                long oneSec = 1000;
                isSleeping = true;
                playerThread.sleep(oneSec);
                isSleeping = false;
            } catch (InterruptedException e){}
        }
    }

    public int score() {
        return score;
    }

    /**
     * Our functions
     */
    public boolean isHuman(){
        return human;
    }

}
