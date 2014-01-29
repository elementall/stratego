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

private Piece[] grid = new Piece[144];
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

		for (int i = 0; i < 12; i++)
			setPiece(i,water);
		for (int i = 0; i < 144; i+=12)
			setPiece(i,water);
		for (int i = 11; i < 144; i+=12)
			setPiece(i,water);
		for (int i = 132; i < 144; i++)
			setPiece(i,water);
	}

	public boolean isValid(int i)
	{
		return grid[i] != water;
	}


	public Piece getPiece(int i) 
	{
		return grid[i];
	}

	public Piece getPiece(int x, int y) 
	{
		return getPiece(getIndex(x, y));
	}

	public int getIndex(int x, int y)
	{
		return x + 1 + (y+1) * 12;
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
		for (int i=0;i<144;i++) {
			if (grid[i] != null && grid[i] != water)
			{
				grid[i] = null;
			}
		}
	}
}
