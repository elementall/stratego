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


public class Grid 
{
	// The actual stratego board is 10x10 but
	// an old performance enhancing trick is to surround the
	// board with illegal squares
	// so that illegal moves are easily discarded

	private Piece[] grid = new Piece[133];
	private static Piece water = new Piece(UniqueID.get(), -1, Rank.WATER);

	protected static class UniqueID
        {
                private static int id = 0;

                static public int get()
                {
                        id++;
                        return id;
                }
        }
	
	public Grid() 
	{
		setPiece(2,4,water);
		setPiece(3,4,water);
		setPiece(2,5,water);
		setPiece(3,5,water);
		setPiece(6,4,water);
		setPiece(7,4,water);
		setPiece(6,5,water);
		setPiece(7,5,water);

		for (int i = 0; i < 11; i++)
			setPiece(i,water);
		for (int i = 0; i < 132; i+=11)
			setPiece(i,water);
		for (int i = 121; i < 133; i++)
			setPiece(i,water);
		int j = 0;
	}

	public Grid(Grid g)
	{
		grid = g.grid.clone();
	}

	public boolean isValid(int i)
	{
		return grid[i] != water;
	}

	public Piece getPiece(int i) 
	{
		return grid[i];
	}

	static public int getX(int i)
	{
		return i % 11 - 1;
	}

	static public int getY(int i)
	{
		return i / 11 - 1;
	}

	public Piece getPiece(int x, int y) 
	{
		return getPiece(getIndex(x, y));
	}

	static public int getIndex(int x, int y)
	{
		return x + 1 + (y+1) * 11;
	}

	public void setPiece(int i, Piece p) 
	{
		grid[i] = p;
	}

	public void setPiece(int x, int y, Piece p) 
	{
		setPiece(getIndex(x, y), p);
	}

	public void clear()
	{
		for (int i=0;i<132;i++) {
			if (grid[i] != null && grid[i] != water)
			{
				grid[i] = null;
			}
		}
	}

	// number of steps between indicies
	static public int steps(int f, int t)
	{
		return Math.abs(getY(t)-getY(f)) + Math.abs(getX(t)-getX(f));
	}


	// search for closest relevant piece to Piece p
	public int closestPieceDir(Piece p, boolean isBombed)
	{
		int tx = Grid.getX(p.getIndex());
		int ty = Grid.getY(p.getIndex());
	
		int minsteps = 99;
		int dir = 0;
		for (int y = 0; y < 10; y++)
		for (int x = 0; x < 10; x++) {
			Piece fp = getPiece(x,y);
			if (fp == null
				|| !fp.hasMoved()
				||  fp.getColor() == p.getColor())
				continue;

			// when looking for the closest relevant piece
			// to a bomb, only look for eights and unknowns
			if (isBombed
				&& fp.getApparentRank() != Rank.UNKNOWN
				&& fp.getApparentRank() != Rank.EIGHT)
				continue;

			int steps = Math.abs(ty - y) + Math.abs(tx - x);
			if (steps < minsteps) {
				minsteps = steps;
				if (y > ty) {
					if (x > tx)
						dir = 12;
					else if (x == tx)
						dir = 11;
					else
						dir = 10;
				} else if (y == ty) {
					if (x > tx)
						dir = 1;
					else
						dir = -1;
				} else {
					if (x > tx)
						dir = -10;
					else if (x == tx)
						dir = -11;
					else
						dir = -12;
				}
			}
		}

		return dir;
	}
}
