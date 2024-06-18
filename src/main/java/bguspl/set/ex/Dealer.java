package bguspl.set.ex;

import bguspl.set.Env;

import java.lang. Math;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Queue;
import java.util.Random;


/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

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
    /**
     * Our fields
     */
    /**
     * Queue for all players that declared of a set and the dealer needs to check
     */
    protected Queue<Integer> declaredSets;
    protected List<Integer> toRemove;
    protected volatile boolean stopPlayers;
    protected volatile boolean noSets;
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        this.declaredSets = new LinkedList<Integer>();
        this.toRemove = new LinkedList<>() ;
        this.stopPlayers = false;
        this.noSets = false;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (Player p: players){
            Thread playerThread = new Thread(p);
            playerThread.start();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            updateTimerDisplay(true);
            table.hints();
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
            if(noSets){
                break;
            }

        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for(int i=players.length-1;i>=0;i--){
            players[i].terminate();
        }
        terminate = true;
    }

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
        if (!declaredSets.isEmpty()){
            if(checkSetAndJudge()){
                    for (Integer i : toRemove) {
                        synchronized (table.cardToSlot[i]) {
                            for (Player player : players) {
                                synchronized (player.isItSet) {
                                    if(table.cardToSlot[i]!=null) {
                                        if (declaredSets.contains(player.id) && table.tokensPlayers[player.id][table.cardToSlot[i]] == 1) {
                                            declaredSets.remove(player.id);
                                            player.removedFromQ = true;
                                            player.isItSet.notifyAll();
                                        }
                                    }
                                }
                                if(table.cardToSlot[i]!=null) {
                                    table.removeToken(player.id, table.cardToSlot[i]);
                                }
                            }
                            if(table.cardToSlot[i]!=null){
                                table.removeCard(table.cardToSlot[i]);
                            }

                        }
                    }
                    toRemove.clear();
                    updateTimerDisplay(true);
            }
        }
    }

    /**
     * Checks if 3 tokens of a player are set
     */

    private boolean checkSetAndJudge(){
        Player toCheck = players[declaredSets.remove()];
        int[] tokens = table.tokensPlayers[toCheck.id];
        int[] maybeSet = new int[env.config.featureSize];
        int counter = 0;
        for (int i = 0; i < env.config.tableSize && counter < env.config.featureSize; i++) {
            if (tokens[i] == 1) {
                maybeSet[counter] = table.slotToCard[i];
                counter++;
            }
        }
        boolean ans = env.util.testSet(maybeSet);
        synchronized (toCheck.isItSet) {
            toCheck.isItSet.add(ans);
            if (ans) {
                for(int i:maybeSet){
                    if(table.cardToSlot[i]!=null){
                        synchronized (table.cardToSlot[i]){
                            toRemove.add(i);
                        }
                    }
                }
                toCheck.isItSet.notifyAll();
                return ans;
            } else {
                toCheck.isItSet.notifyAll();
                return ans;
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        stopPlayers = true;
        List<Integer> randomSlot = new LinkedList<>();
        for (Integer i = 0; i < env.config.tableSize; i++) {
            randomSlot.add(i);
        }
        Collections.shuffle(randomSlot);
        for (int i = 0; i < randomSlot.size(); i++) {
            if (table.slotToCard[randomSlot.get(i)] == null) {
                synchronized (deck) {
                    if (!deck.isEmpty()) {
                        Collections.shuffle(deck);
                        int card = deck.remove(0);
                        table.placeCard(card,randomSlot.get(i));
                    }
                }
            }
        }
        if(deck.isEmpty() && table.countCards()==0){
            noSets = true;
        }
        stopPlayers = false;
    }


    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (declaredSets){
            if(declaredSets.isEmpty()){
                try{
                    if(reshuffleTime - System.currentTimeMillis() > env.config.turnTimeoutWarningMillis){
                        long oneSec = 1000;
                        Thread.sleep(oneSec);
                    }
                    else{
                        long tenMilisec = 10;
                        Thread.sleep(tenMilisec);
                    }
                }catch (InterruptedException ignored){}
            }

        }


    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(Math.max((reshuffleTime - System.currentTimeMillis()),0), false);
        }
        else if (reshuffleTime - System.currentTimeMillis() > env.config.turnTimeoutWarningMillis){
            env.ui.setCountdown(Math.max((reshuffleTime - System.currentTimeMillis()),0), false);
        }
        else{
            env.ui.setCountdown(Math.max((reshuffleTime - System.currentTimeMillis()),0), true);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        synchronized (this)
        {
            stopPlayers = true;
        }

        List<Integer> randomSlot = new LinkedList<>();
        for(Integer i=0; i< env.config.tableSize ; i++){
            randomSlot.add(i);
        }
        Collections.shuffle(randomSlot);
            for (int i = 0; i < randomSlot.size(); i++) {
                for (Player p : players)
                    table.removeToken(p.id, randomSlot.get(i));
                if (table.slotToCard[randomSlot.get(i)] != null) {
                    int card = table.slotToCard[randomSlot.get(i)];
                    table.removeCard(randomSlot.get(i));
                    synchronized (deck) {
                        deck.add(card);
                    }
                }
        }
        stopPlayers = false;
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        List<Integer> winners = new LinkedList<>();
        int maxScore = 0;
        for(Player p: players){
            if(p.score()>maxScore){
                winners.clear();
                winners.add(p.id);
                maxScore=p.score();
            }
            else if(p.score()==maxScore){
                winners.add(p.id);
            }
        }
        int[] winnersArray = new int[winners.size()];
        int count=0;
        for(Integer i: winners){
            winnersArray[count] = (int)i;
            count++;
        }
        env.ui.announceWinner(winnersArray);
    }
}
