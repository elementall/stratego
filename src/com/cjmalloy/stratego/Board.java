/*
    This file is part of Stratego.

    Stratego is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Stratego is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Stratego.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.cjmalloy.stratego;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.locks.ReentrantLock;


//
// The board class contains all the factual information
// about the current and historical position
//
public class Board
{
	protected static class UniqueID
	{
		private static int id = 0;
		
		static public int get()
		{
			id++;
			return id;
		}
	}

        public class UndoMove extends Move
        {
                public Piece tp = null;

		public UndoMove(Piece fpin, Piece tpin, int f, int t)
		{
			super(fpin, f, t);
			tp = tpin;
		}
        }

        public ArrayList<UndoMove> undoList = new ArrayList<UndoMove>();

	
	public static final int RED  = 0;
	public static final int BLUE = 1;
	public static final Spot IN_TRAY = new Spot(-1, -1);
	
	protected Grid grid = new Grid();
	protected ArrayList<Piece> tray = new ArrayList<Piece>();
	protected static ArrayList<Piece> red = new ArrayList<Piece>();
	protected static ArrayList<Piece> blue = new ArrayList<Piece>();
	// setup contains the original locations of Pieces
	// as they are discovered.
	// this is used to guess flags and other bombs
	// TBD: try to guess the opponents entire setup by
	// correllating against all setups in the database
	protected Rank[] setup = new Rank[121];
	protected static int[] dir = { -11, -1,  1, 11 };
	protected static int[] dir2 = { -10, -11, -12, -1,  1, 10, 11, 12 };
	
	public Board()
	{

		//create pieces
		red.add(new Piece(UniqueID.get(), RED, Rank.FLAG));
		red.add(new Piece(UniqueID.get(), RED, Rank.SPY));
		red.add(new Piece(UniqueID.get(), RED, Rank.ONE));
		red.add(new Piece(UniqueID.get(), RED, Rank.TWO));
		for (int j=0;j<2;j++)
			red.add(new Piece(UniqueID.get(), RED, Rank.THREE));
		for (int j=0;j<3;j++)
			red.add(new Piece(UniqueID.get(), RED, Rank.FOUR));
		for (int j=0;j<4;j++)
			red.add(new Piece(UniqueID.get(), RED, Rank.FIVE));
		for (int j=0;j<4;j++)
			red.add(new Piece(UniqueID.get(), RED, Rank.SIX));
		for (int j=0;j<4;j++)
			red.add(new Piece(UniqueID.get(), RED, Rank.SEVEN));
		for (int j=0;j<5;j++)
			red.add(new Piece(UniqueID.get(), RED, Rank.EIGHT));
		for (int j=0;j<8;j++)
			red.add(new Piece(UniqueID.get(), RED, Rank.NINE));
		for (int j=0;j<6;j++)
			red.add(new Piece(UniqueID.get(), RED, Rank.BOMB));

		//create pieces
		blue.add(new Piece(UniqueID.get(), BLUE, Rank.FLAG));
		blue.add(new Piece(UniqueID.get(), BLUE, Rank.SPY));
		blue.add(new Piece(UniqueID.get(), BLUE, Rank.ONE));
		blue.add(new Piece(UniqueID.get(), BLUE, Rank.TWO));
		for (int j=0;j<2;j++)
			blue.add(new Piece(UniqueID.get(), BLUE, Rank.THREE));
		for (int j=0;j<3;j++)
			blue.add(new Piece(UniqueID.get(), BLUE, Rank.FOUR));
		for (int j=0;j<4;j++)
			blue.add(new Piece(UniqueID.get(), BLUE, Rank.FIVE));
		for (int j=0;j<4;j++)
			blue.add(new Piece(UniqueID.get(), BLUE, Rank.SIX));
		for (int j=0;j<4;j++)
			blue.add(new Piece(UniqueID.get(), BLUE, Rank.SEVEN));
		for (int j=0;j<5;j++)
			blue.add(new Piece(UniqueID.get(), BLUE, Rank.EIGHT));
		for (int j=0;j<8;j++)
			blue.add(new Piece(UniqueID.get(), BLUE, Rank.NINE));
		for (int j=0;j<6;j++)
			blue.add(new Piece(UniqueID.get(), BLUE, Rank.BOMB));

		tray.addAll(red);
		tray.addAll(blue);

		Collections.sort(tray);
	}

	public Board(Board b)
	{
		grid = new Grid(b.grid);
				
		tray.addAll(b.tray);
		undoList.addAll(b.undoList);
		setup = b.setup.clone();
	}

	public boolean add(Piece p, Spot s)
	{
		if (p.getColor() == Settings.topColor)
		{
			if(s.getY() > 3)
				return false;
		}
		else
		{
			if (s.getY() < 6)
				return false;
		}
			
		if (getPiece(s) == null)
		{
			grid.setPiece(s.getX(), s.getY(), p);
			tray.remove(p);
			return true;
		}
		return false;
	}

	// TRUE if valid attack
	public boolean attack(BMove m)
	{
		if (validAttack(m))
		{
			Piece fp = getPiece(m.getFrom());
			Piece tp = getPiece(m.getTo());
			moveHistory(fp, tp, m.getFrom(), m.getTo());

			fp.setShown(true);
			fp.setKnown(true);
			tp.setKnown(true);
			fp.setMoved(true);
			fp.moves++;
			if (!Settings.bNoShowDefender || fp.getRank() == Rank.NINE) {
				tp.setShown(true);
			}

			// TBD: store original setup location
			// after each discovery
			if (tp.getRank() == Rank.BOMB)
				setup[m.getTo()] = Rank.BOMB;
			
			int result = fp.getRank().winFight(tp.getRank());
			if (result == 1)
			{
				remove(m.getTo());
				setPiece(fp, m.getTo());
				setPiece(null, m.getFrom());
			}
			else if (result == -1)
			{
				remove(m.getFrom());
				remove(m.getTo());
			}
			else 
			{
				if (Settings.bNoMoveDefender ||
						tp.getRank() == Rank.BOMB)
					remove(m.getFrom());
				else if (fp.getRank() == Rank.NINE)
					scoutLose(m);
				else
				{
					remove(m.getFrom());
					setPiece(tp, m.getFrom());
					setPiece(null, m.getTo());
				}
			}
			return true;
		}
		return false;
	}

	public int checkWin()
	{
		int flags = 0;
		int flagColor = -1;
		for (int i=0;i<10;i++)
		for (int j=0;j<10;j++)
			if (getPiece(i, j) != null)
			if (getPiece(i, j).getRank().equals(Rank.FLAG))
			{
				flagColor = grid.getPiece(i,j).getColor();
				flags++;
			}
		if (flags!=2)
			return flagColor;
		
		for (int k=0;k<2;k++)
		{
			int movable = 0;
			for (int i=0;i<10;i++)
			for (int j=0;j<10;j++)
			{
				if (getPiece(i, j) != null)
				if (getPiece(i, j).getColor() == k)
				if (!getPiece(i, j).getRank().equals(Rank.FLAG))
				if (!getPiece(i, j).getRank().equals(Rank.BOMB))
				if (getPiece(i+1, j) == null ||
					getPiece(i+1, j).getColor() == (k+1)%2||
					getPiece(i, j+1) == null ||
					getPiece(i, j+1).getColor() == (k+1)%2||
					getPiece(i-1, j) == null ||
					getPiece(i-1, j).getColor() == (k+1)%2||
					getPiece(i, j-1) == null ||
					getPiece(i, j-1).getColor() == (k+1)%2)
					
					movable++;
			}
			
			if (movable==0)
				return (k+1)%2;
		}
		
		return -1;
	}
	
	public void clear()
	{
		grid.clear();

		tray.clear();
		// put all the pieces in the tray
		tray.addAll(red);
		tray.addAll(blue);

		Collections.sort(tray);

		// clear all the dynamic piece data
		for (Piece p: tray)
			p.clear();

		undoList.clear();

		for (int i = 11; i <=120; i++)
			setup[i] = Rank.UNKNOWN;
	}
	
	public Piece getPiece(int x, int y)
	{
		return grid.getPiece(x, y);
	}
	
	public boolean isValid(int i)
	{
		return grid.isValid(i);
	}
	
	public Piece getPiece(int i)
	{
		return grid.getPiece(i);
	}
	
	public Piece getPiece(Spot s)
	{
		return grid.getPiece(s.getX(),s.getY());
	}

	
	public Piece getTrayPiece(int i)
	{
		return tray.get(i);
	}
	
	public int getTraySize()
	{
		return tray.size();
	}

	public void showAll()
	{
		for (int i=0;i<10;i++)
		for (int j=0;j<10;j++)
			if (grid.getPiece(i, j) != null)
				grid.getPiece(i,j).setShown(true);
		for (Piece p: tray)
			p.setShown(true);
	}
	
	public void hideAll()
	{
		for (int i=0;i<10;i++)
		for (int j=0;j<10;j++)
			if (grid.getPiece(i, j) != null)
				grid.getPiece(i,j).setShown(false);

		hideTray();
	}
	
	public void hideTray()
	{
		for (Piece p: tray)
			p.setShown(false);
	}
	
	protected void setPiece(Piece p, Spot s)
	{
		grid.setPiece(s.getX(),s.getY(),p);
	}
	
	protected void setPiece(Piece p, int i)
	{
		grid.setPiece(i, p);
	}

	public UndoMove getLastMove()
	{
		return undoList.get(undoList.size()-1);
	}


	protected void moveHistory(Piece fp, Piece tp, int from, int to)
	{
		if (tp != null)
			tp = new Piece(tp);
		undoList.add(new UndoMove(new Piece(fp), tp, from, to));

		// Acting rank is a historical property of the known
		// opponent piece ranks that a moved piece was adjacent to.
		//
		// If it moves next to a known opponent piece, 
		// it inherits an acting rank of a equal to or lower than the
		// known piece.
		//
		// If it moves away from a known opponent piece,
		// it inherits an acting rank of a equal to or higher than the
		// known piece.
		//
		// Acting rank is calculated factually, but is of
		// questionable use in the heuristic because of bluffing.
		//

		// movement towards
		for (int d : dir) {
			int i = to + d;
			if (!isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p != null
				&& p.getColor() != fp.getColor()
				&& p.isKnown()) {
				Rank rank = p.getRank();
				Rank arank = fp.getActingRankLow();
				if (arank == Rank.NIL
				|| arank.toInt() > rank.toInt())
					fp.setActingRankLow(rank);
			}
		}

		// movement aways
		for (int d : dir) {
			int i = from + d;
			if (!isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p != null
				&& p.getColor() != fp.getColor()
				&& p.isKnown()) {
				Rank rank = p.getRank();
				Rank arank = fp.getActingRankHigh();
				if (arank == Rank.NIL
				|| arank.toInt() < rank.toInt())
					fp.setActingRankHigh(rank);
			}
		}
	}
	
	public boolean move(BMove m)
	{
		if (getPiece(m.getTo()) != null)
			return false;

		if (validMove(m))
		{
			Piece fp = getPiece(m.getFrom());
			moveHistory(fp, null, m.getFrom(), m.getTo());

			setPiece(fp, m.getTo());
			setPiece(null, m.getFrom());
			fp.setMoved(true);
			fp.moves++;
			if (Math.abs(m.getToX() - m.getFromX()) > 1 || 
				Math.abs(m.getToY() - m.getFromY()) > 1) {
				//scouts reveal themselves by moving more than one place
				fp.setShown(true);
				fp.setKnown(true);
			}

			return true;
		}
		
		return false;
	}
	
	public boolean remove(int i)
	{
		if (getPiece(i) == null)
			return false;
		
		getPiece(i).setShown(true);
		getPiece(i).setKnown(false);

		for (Piece p : tray) 
			p.setKnown(true);
		tray.add(getPiece(i));
		setPiece(null, i);
		Collections.sort(tray);
		return true;
	}

	public boolean remove(Spot s)
	{
		return remove(grid.getIndex(s.getX(), s.getY()));
	}

	// Three Moves on Two Squares: Two Square Rule.
	//
	// It is not allowed to move a piece more than 3
	// times non-stop between the same two
	// squares, regardless of what the opponent is doing.
	//
	// The restriction here is more strict.
	// It prevents a piece from moving more than 2 times
	// non-stop betwen the same two squares.
	public boolean isRecentMove(BMove m)
	{
		int size = undoList.size();
		if (size < 4)
			return false;
		return m.equals(undoList.get(size-4));
	}

	// Repetition of Threatening Moves: More-Squares Rule
	// It is not allowed to continuously chase one or
	// more pieces of the opponent endlessly. The
	// continuous chaser may not play a chasing
	// move which would lead to a position on the
	// board which has already taken place.
	// TBD: need a transposition table
	// TBD

	public void undoLastMove()
	{
		int size = undoList.size();
		if (size < 2)
			return;
		for (int j = 0; j < 2; j++, size--) {
			UndoMove undo = undoList.get(size-1);
			Piece tp = getPiece(undo.to);
			setPiece(undo.getPiece(), undo.from);
			setPiece(undo.tp, undo.to);
			if (undo.tp != null) {
				if (tp == null) {
					tray.remove(tray.indexOf(undo.tp));
					tray.remove(tray.indexOf(undo.getPiece()));
				} else if (tp.equals(undo.tp))
					tray.remove(tray.indexOf(undo.getPiece()));
				else
					tray.remove(tray.indexOf(undo.tp));
			}
			undoList.remove(size - 1);
		}
		Collections.sort(tray);
	}


	private void scoutLose(BMove m)
	{
		Spot tmp = getScoutLooseFrom(m);

		remove(m.getFrom());
		setPiece(getPiece(m.getTo()), tmp);
		setPiece(null, m.getTo());
	}
	
	private Spot getScoutLooseFrom(BMove m)
	{
		if (m.getFromX() == m.getToX())
		{
			if (m.getFromY() < m.getToY())
				return new Spot(m.getToX(), m.getToY() - 1);
			else
				return new Spot(m.getToX(), m.getToY() + 1);
		}
		else //if (m.getFromY() == m.getToY())
		{
			if (m.getFromX() < m.getToX())
				return new Spot(m.getToX() - 1, m.getToY());
			else
				return new Spot(m.getToX() + 1, m.getToY());
		}
	}
	
	protected boolean validAttack(BMove m)
	{
		if (m.getToX()<0||m.getToX()>9||
			m.getToY()<0||m.getToY()>9)
			return false;
		if (getPiece(m.getTo()) == null)
			return false;
		if (getPiece(m.getFrom()) == null)
			return false;
		if (getPiece(m.getFrom()).getColor() ==
			getPiece(m.getTo()).getColor())
			return false;
		if (getPiece(m.getTo()).getRank().equals(Rank.WATER))
			return false;

		return validMove(m);
	}

	// TRUE if piece moves to legal square
	public boolean validMove(BMove m)
	{
		if (m.getToX()<0||m.getToX()>9||
			m.getToY()<0||m.getToY()>9)
			return false;
		Piece fp = getPiece(m.getFrom());
		if (fp == null)
			return false;
		
		//check for rule: "a player may not move their piece back and fourth.." or something
		if (isRecentMove(m))
			return false;

		switch (fp.getRank())
		{
		case FLAG:
		case BOMB:
			return false;
		}

		if (m.getFromX() == m.getToX())
			if (Math.abs(m.getFromY() - m.getToY()) == 1)
				return true;
		if (m.getFromY() == m.getToY())
			if (Math.abs(m.getFromX() - m.getToX()) == 1)
				return true;

		if (fp.getRank().equals(Rank.NINE))
			return validScoutMove(m.getFromX(), m.getFromY(), m.getToX(), m.getToY(), fp);

		return false;
	}
	
	private boolean validScoutMove(int x, int y, int tox, int toy, Piece p)
	{
		if ( !(grid.getPiece(x,y) == null || p.equals(grid.getPiece(x,y))) )
			return false;

		if (x == tox)
		{
			if (Math.abs(y - toy) == 1)
				return true;
			if (y - toy > 0)
				return validScoutMove(x, y - 1, tox, toy, p);
			if (y - toy < 0)
				return validScoutMove(x, y + 1, tox, toy, p);
		}
		else if (y == toy)
		{
			if (Math.abs(x - tox) == 1)
				return true;
			if (x - tox > 0)
				return validScoutMove(x - 1, y, tox, toy, p);
			if (x - tox < 0)
				return validScoutMove(x + 1, y, tox, toy, p);
		}

		return false;
	}
}

