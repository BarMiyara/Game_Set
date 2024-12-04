package bguspl.set.ex;

import java.util.ArrayList;
import java.util.Vector;

public class Tokens implements Comparable<Tokens> {



    private Vector<Integer> slots;
    private final int capacity;
    private final int playerId;
    private volatile boolean terminate;
    private volatile boolean needToRemove;
    private int[] setForTest;
    //check
    private final boolean isFlag;






    Tokens(){
        this.playerId = -1;
        this.capacity = 0;
        terminate = false;
        isFlag = true;
    }
    Tokens(int capacity, int playerId) {
        this.capacity = capacity;
        this.playerId = playerId;
        slots = new Vector<>();
        terminate = false;
        needToRemove = false;
        isFlag = false;

    }

    public int getPlayerId(){ return playerId;}

    public synchronized int[] getSlots()
    {   int [] numsOfSlots = new int [capacity];
        for(int i = 0; i< slots.size(); i++) numsOfSlots[i] = slots.get(i);
        return numsOfSlots;
    }
    public synchronized boolean putOrTakeToken(Integer slot)
    {if(slots.size() == capacity || slots.contains(slot))
        {   needToRemove = false;
            slots.remove(slot);
            return false;
        } else{
            slots.add(slot);
            return true;}
    }




    public void setCardsForTest(int[] setForCheck){setForTest = setForCheck;}
    public int[] getCardsForTest(){return setForTest;}
    public void clear(){slots = new Vector<>();}
    public boolean terminate(){return terminate;}
    public synchronized boolean full(){return slots.size()==capacity;}
    public boolean needToRemove() { return needToRemove;}
    public void setNeedToRemove(boolean turnOnOrOff){needToRemove = turnOnOrOff;}
    public boolean containSlot(int slot) { return slots.contains(slot);}
    public synchronized boolean withProblem(){ return slots.isEmpty();}
    public void turnOff(){terminate = true;}
    //check
    public boolean isFlag(){return isFlag;}

    @Override
    public int compareTo(Tokens o) {
        if(this.isFlag && !o.isFlag) return 1;
        else if ((!this.isFlag&&!o.isFlag)||(this.isFlag&&o.isFlag)) return 0;
        else return -1;
    }
}
