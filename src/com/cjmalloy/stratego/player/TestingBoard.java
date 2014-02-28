/*.
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

package com.cjmalloy.stratego.player;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import com.cjmalloy.stratego.Board;
import com.cjmalloy.stratego.BMove;
import com.cjmalloy.stratego.Grid;
import com.cjmalloy.stratego.Move;
import com.cjmalloy.stratego.Piece;
import com.cjmalloy.stratego.Rank;
import com.cjmalloy.stratego.Spot;
import com.cjmalloy.stratego.Settings;
import java.util.Random;



public class TestingBoard extends Board
{
	private static final int DEST_PRIORITY_DEFEND_FLAG = 20;
	private static final int DEST_PRIORITY_DEFEND_FLAG_BOMBS = 10;
	private static final int DEST_PRIORITY_ATTACK_FLAG = 10;
	private static final int DEST_PRIORITY_ATTACK_FLAG_BOMBS = 5;
	private static final int DEST_PRIORITY_LOW = -1;
	private static final int DEST_VALUE_NIL = -99;

	protected int[] invincibleRank = new int[2];	// rank that always wins attack
	protected int[][] knownRank = new int[2][15];	// discovered ranks
	protected int[][] activeRank = new int[2][15];	// moved rank indes
	protected int[][] activeRankID = new int[2][15];	// moved rank ID
	protected int[][] trayRank = new int[2][15];	// ranks in trays
	protected boolean[][] neededRank = new boolean[2][15];	// needed ranks
	protected int[][][] destValue = new int[2][15][121];	// encourage forward motion
	protected int[] destTmp = new int[121];	// encourage forward motion
	protected int[][] winRank = new int[15][15];	// winfight cache


	protected boolean possibleSpy = false;
	protected int value;	// value of board

	static final int [] values = {
		0,
		400,	// 1 Marshal
		200, 	// 2 General
		100, 	// 3 Colonel
		75,	// 4 Major
		45,	// 5 Captain
		30,	// 6 Lieutenant
		25,	// 7 Sergeant
		60,	// 8 Miner
		25,	// 9 Scout
		200,	// Spy
		0,	// Bomb
		6000,	// Flag
		45,	// Unknown
		0	// Nil
	};

	static final int [][] bombPattern = {
		{ 12, 13, 23, 23 },	// corner
		{ 14, 13, 25, 15 },	// back row
		{ 15, 14, 26, 16 },	// back row
		{ 16, 15, 27, 17 },	// back row
		{ 17, 16, 28, 18 },	// back row
		{ 18, 17, 29, 19 },	// back row
		{ 19, 18, 30, 20 },	// back row
		{ 21, 20, 32, 32 },	// corner
		{ 12, 35, 45, 25 },	// corner double layer
		{ 21, 42, 54, 30 }	// corner double layer
	};

	public TestingBoard() {}
	
	public TestingBoard(Board t)
	{
		super(t);

		value = 0;

		for (int c = RED; c <= BLUE; c++)
		for (int j=0;j<15;j++) {
			knownRank[c][j] = 0;
			activeRank[c][j] = 0;
			activeRankID[c][j] = 0;
			trayRank[c][j] = 0;
			neededRank[c][j] = false;
		}
		// ai only knows about known opponent pieces
		// so update the grid to only what is known
		for (int i=12;i<=120;i++) {
			if (!isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p != null) {
				// copy pieces to be threadsafe
				// because piece data is updated during ai process
				Piece np = new Piece(p);
				grid.setPiece(i, np);
				np.setAiValue(0);
				if (!p.isKnown()) {
					if (p.getColor() == Settings.bottomColor)
						np.setRank(Rank.UNKNOWN);
			 	} else {
					int r = p.getRank().toInt();
					knownRank[p.getColor()][r-1]++;
				}

				if (p.hasMoved() || p.isKnown()) {
					// count the number of moved pieces
					// to determine further movement penalties
					Rank rank = p.getRank();
					int r = rank.toInt();
					activeRank[p.getColor()][r-1] = i;
					activeRankID[p.getColor()][r-1]=p.getID();
				}
			}
		}

		// add in the tray pieces to knownRank and to trayRank
		for (int i=0;i<getTraySize();i++) {
			Piece p = getTrayPiece(i);
			int r = p.getRank().toInt();
			knownRank[p.getColor()][r-1]++;
			trayRank[p.getColor()][r-1]++;
		}

		// How to guess whether the marshal can be attacked by a spy.
		//
		// The most conservative approach is to assume that
		// any unknown piece could be a spy, if the spy is
		// still on the board.
		//
		// The approach taken is that if a piece
		// has an actingRankLow, then it is not a spy.
		// That is because it is unlikely (although possible) that
		// that the opponent would have moved its spy into a position
		// where it could be attacked.
		possibleSpy = (knownRank[Settings.bottomColor][9] == 0);

		// a rank becomes invincible when all lower ranking pieces
		// are gone or known
		for (int c = RED; c <= BLUE; c++) {
			int rank = 0;
			for (;rank<9;rank++) {
				if (knownRank[c][rank] != Rank.getRanks(rank))
					break;
			}
			invincibleRank[1-c] = rank + 1;
		}

		// Destination Value Matrices
		//
		// Note: at depths >8 moves (16 ply),
		// these matrices may not necessary
		// because these pieces will find their targets in the 
		// move tree.  However, they would still be useful in pruning
		// the move tree.
		for (int c = RED; c <= BLUE; c++)
		for (int rank = 0; rank < 15; rank++) {
			for (int j=12; j <= 120; j++)
				destValue[c][rank][j] = DEST_VALUE_NIL;
		}

		for (int i=12;i<=120;i++) {
			if (!isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p != null) {
			Rank rank = p.getRank();

			// Encourage lower ranked pieces to find pieces
			// of higher ranks.
			//
			// Note: this is a questionable heuristic
			// because the ai doesn't know what to do once
			// the piece reaches its destination.
			// Only 1 rank is assigned to chase an opponent piece
			// to prevent a pile-up of ai pieces
			// chasing one opponent piece that is protected
			// by an unknown and possibly lower piece.
			if (p.isKnown()) {
				int r = rank.toInt();
				if (r >= 2 && r <= 7)  {
					genDestTmp(p.getColor(), i, DEST_PRIORITY_LOW);
					boolean found = false;
					for (int j = r - 1; j >= 1; j--)
					if (rankAtLarge(1-p.getColor(),j)) {
						genNeededDest(1-p.getColor(), j);
						found = true;
						break;
					}
					if (!found) {
						// go for an even exchange
						genNeededDest(1-p.getColor(), r);
					}
					
				}
			} else if (!p.hasMoved()) {
				// Encourage lower ranked pieces to discover
				// unknown and unmoved pieces
				// Back rows may be further, but are of
				// equal destination value.
				int y = Grid.getY(i);
				if (y <= 3)
					y = -y;
				genDestTmp(p.getColor(), i, DEST_PRIORITY_LOW + y - 9);
				genDestValue(1-p.getColor(), 5);
				genDestValue(1-p.getColor(), 6);
				genDestValue(1-p.getColor(), 7);
				genDestValue(1-p.getColor(), 9);

				// ai flag discovery
				if (p.getRank() == Rank.FLAG)
					flagSafety(i);
			}
			}
		}

		// keep a piece of a high rank in motion,
		// preferably a 6, 7 or 9 to discover unknown pieces.
		// 5s are also drawn towards unknown pieces, but they
		// are more skittish and therefore blocked by moving
		// unknown pieces.
		for (int c = RED; c <= BLUE; c++) {
			int rank[] = { 6, 7, 9 };
			boolean found = false;
			for ( int r : rank )
				if (activeRank[c][r-1] != 0) {
					found = true;
					break;
				}

			// if there are not any high rank pieces in motion
			// set the neededRank.  The search tree will
			// move the rank that can make the most progress
			// towards a opponent unknown unmoved piece.
			if (!found) {
				for (int r : rank)
					neededRank[c][r-1] = true;
			}
		}

		possibleBomb();
		possibleFlag();
		valueLaneBombs();
		genWinRank();
	}

	// cache the winFight values for faster evaluation
	private void genWinRank()
	{
		for (Rank frank : Rank.values())
		for (Rank trank : Rank.values()) {
			int v;
			if (frank == Rank.UNKNOWN) {
				// ai must be defender

				// The following results are unknown
				// but good guesses based simply on rank
				// and some board information.
				// The ai assumes that any unknown piece
				// will take a bomb because of flag safety.
				// Any piece will take a SPY
				// And the eight is also likely to lose
				// and too valuable to risk
				// in an unknown exchange.
				if (trank == Rank.BOMB && rankAtLarge(Settings.bottomColor, 8)
					|| trank == Rank.FLAG
					|| trank == Rank.SPY
					|| trank == Rank.EIGHT)
					v = Rank.WINS;
				else
					v = frank.winFight(trank);
			} else if (trank == Rank.UNKNOWN) {
				// ai must be attacker

				// The ai eights are only used to
				// attack bombs.  When the ai discovers
				// a possible bomb, it sets the rank to bomb.
				// So an eight attack on any other piece
				// is considered lost.
				if (frank == Rank.EIGHT)
					v = Rank.LOSES;
				else
					v = frank.winFight(trank);
			} else
				v = frank.winFight(trank);
			winRank[frank.toInt()][trank.toInt()] = v;
		}
	}


	private boolean rankAtLarge(int color, int rank)
	{
		return (trayRank[color][rank-1] != Rank.getRanks(rank-1));
	}

	private void flagSafety(int flag)
	{
		Piece pflag = getPiece(flag);

		// ai only code
		if (pflag.getColor() != Settings.topColor)
			return;

		assert pflag.getColor() == Settings.topColor : "flag routines only for ai";
		// If the ai assumes opponent has guessed the flag
		// by setting it known
		// then the ai with leave pieces hanging if the opponent
		// can take the flag.  But the opponent may or may know.
		// pflag.setKnown(true);

		// assume opponent will try to get to it
		// with all known and unknown ranks
		genDestTmp(pflag.getColor(), flag, DEST_PRIORITY_DEFEND_FLAG);
		for (int r = 1; r <= Rank.UNKNOWN.toInt(); r++)
			genDestValue(1-pflag.getColor(), r);

		// defend it by lowest ranked pieces
		for (int r = 1; r <= 7; r++)
			if (rankAtLarge(pflag.getColor(),r)) {
				genDestTmp(1-pflag.getColor(), flag, DEST_PRIORITY_DEFEND_FLAG);
				genDestValue(pflag.getColor(), r);
				break;
			}

		// initially all bombs are worthless (0)
		// value bombs around ai flag
		for (int d : dir) {
			int j = flag + d;
			if (!isValid(j))
				continue;
			Piece p = getPiece(j);
			if (p == null)
				continue;
			if (p.getRank() == Rank.BOMB) {
				p.setAiValue(aiBombValue());

				// assume opponent will try to remove
				// with eights and unknown ranks
				genDestTmp(p.getColor(), j, DEST_PRIORITY_DEFEND_FLAG_BOMBS);
				genDestValue(1-p.getColor(), Rank.UNKNOWN.toInt());
				genDestValue(1-p.getColor(), Rank.EIGHT.toInt());

				// If one of the bombs is known,
				// then the opponent probably has guessed
				// the location of the flag.
				// Try to protect the bombs with the lowest rank(s)
				if (p.isKnown())
					for (int r = 1; r < 8; r++)
						if (rankAtLarge(p.getColor(), r)) {
							genDestTmp(p.getColor(), j + 11, DEST_PRIORITY_DEFEND_FLAG_BOMBS);
							genDestValue(p.getColor(), r);
							break;
						}
			}
		}
	}

	// establish location it as a flag destination.
	private void genDestFlag(int b[])
	{
		int i = b[0];
		Piece flagp = getPiece(i);
		if (flagp.getColor() == Settings.bottomColor) {
			flagp.setRank(Rank.FLAG);
			flagp.setKnown(true);
			// set the flag value higher than a bomb
			// so that a miner that removes a bomb to get at the flag
			// will attack the flag rather than another bomb
			flagp.setAiValue(aiBombValue()+10);
		}
		// else
		// hmmm, currently we are not able to change
		// rank or value for ai pieces

		// Send in a lowly piece to find out
		// Note: if the flag is still bombed, the
		// destination matrix will not extend past the bombs.
		// So the lowly piece will only be activated IF
		// there is an open path to the flag.
		genDestTmp(flagp.getColor(), i, DEST_PRIORITY_ATTACK_FLAG);
		for (int k = 9; k > 0; k--) {
			if (rankAtLarge(1-flagp.getColor(),k)) {
				genNeededDest(1-flagp.getColor(), k);
				break;
			}
		}

		// Set any remaining pieces in the pattern to bomb
		// It doesn't matter if the piece really is a bomb or not.
		boolean found = true;
		for (int j = 1; j < 4; j++) {
			Piece p = getPiece(b[j]);
			if (p == null
				|| (p.isKnown() && p.getRank() != Rank.BOMB)
				|| p.hasMoved()) {
				found = false;
				continue;
			}

			// hmmm, currently we are not able to change
			// rank or value for ai pieces
			if (p.getRank() == Rank.UNKNOWN
				|| p.getRank() == Rank.BOMB) {
				p.setAiValue(aiBombValue());
				p.setKnown(true);
				p.setRank(Rank.BOMB);
			}
		}

		// Remove bombs that surround a possible flag
		// this code is only for opponent bombs
		if (flagp.getColor() == Settings.bottomColor) {
			if (found)
				destBomb(b);
		}
	}

	// Scan the board setup for suspected flag pieces,
	private void possibleFlag()
	{
		for (int c = RED; c <= BLUE; c++) {
		int [] maybe = null;
		int maybe_count = 0;
                for ( int[] bp : bombPattern ) {
			int[] b = new int[4];
			for ( int i = 0; i < 4; i++) {
				if (c == Settings.topColor)
					b[i] = bp[i];
				else
					b[i] = 132 - bp[i];
			}
			Piece flagp = getPiece(b[0]);
			if (flagp != null && !flagp.isKnown() && !flagp.hasMoved()) {
				boolean found = false;
				int k;
				for ( k = 1; k < 4; k++ ) {
					if (setup[b[k]] == Rank.BOMB) {
						found = true;
						continue;
					}
					Piece p = getPiece(b[k]);
					if (p == null
						|| (p.isKnown() && p.getRank() != Rank.BOMB)
						|| p.hasMoved()) {
						found = false;
						break;
					}
					
				}
				if (found) {
					genDestFlag(b);
					break;	// only one pattern at a time
				}
				if (k == 4) {
					// no bombs found, but it looks suspicious
					maybe_count++;
					maybe = b;
				}
			} // possible flag
		} // bombPattern
		if (maybe_count == 1) {
			// didn't find any bombs, but there is only one
			// pattern left, so go for it
			genDestFlag(maybe);
		}

		} // colors
	}

	// Scan the board for isolated unmoved pieces (possible bombs).
	// Reset the piece rank to bomb so that the ai
	// move generation will not generate any moves for the piece
	// and the ai will not want to attack it.
	private void possibleBomb()
	{
		for (int i = 78; i <= 120; i++) {
			if (!isValid(i))
				continue;
			Piece tp = getPiece(i);
			if (tp != null && !tp.isKnown() && !tp.hasMoved()) {
				boolean found = false;
				for (int k =0; k < 4; k++) {
					int j = i + dir[k];
					if (!isValid(j))
						continue;
					Piece p = getPiece(j);
					if (p != null && !p.hasMoved()) {
						found = true;
						break;
					}
				}
				if (!found) {
					// keep the value at zero
					// because an eight may need
					// to remove the useless bomb
					// to get at a needed bomb.
					tp.setRank(Rank.BOMB);
					tp.setKnown(true);
				}
			}
		}
	}

	// Set a destination for a bomb surrounding a possible flag
	// this code is only for opponent bombs

	// The ai can only guess how to launch an attack
	// on a bomb that it thinks may protect the flag.
	// It sends an invincible low ranked piece and an eight,
	// so it cannot be easily blocked by an unknown opponent piece.
	// The idea is once the low ranked piece reaches the bomb area
	// with the eight trailing behind, the search tree will
	// discover a way for the eight to win the bomb.
	private void destBomb(int b[])
	{
		int flag = b[0];
		int j = b[2];	// closest bomb in pattern
		Piece p = getPiece(j);

		// stay near but don't get in the way
		int near =  j - 10;
		if (!isValid(near))
			near = j - 12;
		assert isValid(near) : "near is not valid?";

		// send an invincible low ranked piece ahead
		// to protect the eight
		genDestTmp(p.getColor(), near, DEST_PRIORITY_LOW);
		int r;
		for (r = invincibleRank[1-p.getColor()]; r >= 1; r--)
			if (rankAtLarge(1-p.getColor(), r)) {
				genNeededDest(1-p.getColor(), r);
				break;
		}
		if (r == 0)	// no invincible rank 
			return;	// don't bother trying

		// Send a miner after a low ranked piece
		// secures the area first.
		// r is the low piece rank
		// look in activeRank for the piece that is moving
		// make that piece the destination for the eight
		// this way the eight should tag along behind
		int index = activeRank[1-p.getColor()][r-1];
		if (index != 0) {
			// genDestTmp(p.getColor(), index, DEST_PRIORITY_LOW);
			genDestTmp(p.getColor(), j, DEST_PRIORITY_LOW);
			genNeededDest(1-p.getColor(), 8);
		}
	}

	// some bombs are worth removing and others we can ignore.
	// initially all bombs are worthless (0)
	private void valueLaneBombs()
	{
		// remove bombs that block the lanes
		int [][] frontPattern = { { 78, 79 }, {90, 79}, {82, 83}, {86, 87}, {86, 98} };
		for (int i = 0; i < 5; i++) {
			Piece p1 = getPiece(frontPattern[i][0]);
			Piece p2 = getPiece(frontPattern[i][1]);
			if (p1 != null && p1.getRank() == Rank.BOMB
				&& p2 != null && p2.getRank() == Rank.BOMB) {
				genDestTmp(p1.getColor(), frontPattern[i][0], DEST_PRIORITY_LOW);
				p1.setAiValue(aiBombValue());
				if (p1.getRank() == Rank.UNKNOWN)
					p1.setRank(Rank.BOMB);
				genNeededDest(1-p1.getColor(), 8);

				genDestTmp(p2.getColor(), frontPattern[i][1], DEST_PRIORITY_LOW);
				p2.setAiValue(aiBombValue());
				if (p2.getRank() == Rank.UNKNOWN)
					p2.setRank(Rank.BOMB);
				genNeededDest(1-p2.getColor(), 8);
			}
		}
	}

	private void genNeededDest(int color, int rank)
	{
		genDestValue(color, rank);
		if (activeRank[color][rank-1] == 0)
			neededRank[color][rank-1] = true;
	}

	// Generate a matrix of consecutive values with the highest
	// value at the destination "to".
	//
	// Pieces of "color" or opposing bombs
	// block the destination from discovery.  It is the opposite
	// color of the piece seeking the destination.
	//
	// Pieces of the opposite "color" do not
	// block the destination.
	//
	// Seed the matrix with "n" at "to".
	//
	// This matrix is used to lead pieces to desired
	// destinations.
	private void genDestTmp(int color, int to, int n)
	{
		for (int j = 12; j <= 120; j++)
			destTmp[j] = DEST_VALUE_NIL;
		destTmp[to] = n;
		boolean found = true;
		while (found) {
		found = false;
		for (int j = 12; j <= 120; j++) {
			if (!isValid(j) || destTmp[j] != n)
				continue;
			Piece p = getPiece(j);
			if (p != null
				&& j != to
				&& !p.hasMoved()
				&& (p.getColor() == color || p.getRank() == Rank.BOMB))
				continue;
			for (int d : dir) {
				int i = j + d;
				if (!isValid(i) || destTmp[i] != DEST_VALUE_NIL)
					continue;
				destTmp[i] = n - 1;
				found = true;
			}
		}
		n--;
		}

		// this nulls out incentive for chase sequences
		if (getPiece(to) != null)
			destTmp[to] = DEST_VALUE_NIL;
	}

	private void genDestValue(int color, int rank)
	{
		for (int j = 12; j <= 120; j++)
			if (destValue[color][rank-1][j] < destTmp[j])
				destValue[color][rank-1][j] = destTmp[j];
	}

	public boolean isInvincible(Piece p) 
	{
		return (p.getRank().toInt() <= invincibleRank[p.getColor()]);
	}

	public void move(BMove m, int depth, boolean unknownScoutFarMove)
	{
		Piece fp = getPiece(m.getFrom());
		Rank fprank = fp.getRank();
		Piece tp = getPiece(m.getTo());
		setPiece(null, m.getFrom());
		moveHistory(fp, tp, m);
		int vm = 0;
		int r = fprank.toInt()-1;
		int color = fp.getColor();

		if (tp == null) { // move to open square
			if (!fp.isKnown() && fp.moves == 0)
				vm -= aiMovedValue(fp);

			// give a given piece destValue
			// or any piece of a given rank if the rank is needed
			if ((activeRankID[color][r] == fp.getID() || neededRank[color][r])
				// only give nines destValue for adjacent moves
				// because they can reach their destination
				// more quickly.
				&& !unknownScoutFarMove) {
				int dvto = destValue[color][r][m.getTo()];
				int dvfrom = destValue[color][r][m.getFrom()];
				if (dvto != DEST_VALUE_NIL
					&& dvfrom != DEST_VALUE_NIL)
					vm += dvto - dvfrom;
			}

			setPiece(fp, m.getTo());
			if (unknownScoutFarMove) {
				// Moving an unknown scout
				// more than one position makes it known.
				// That changes its value.
				vm += makeKnown(fp);
				fp.setRank(Rank.NINE);
			}

		} else { // attack
			Rank tprank = tp.getRank();

			// note: use values[] (not aiValue) for ai attacker
			// because unknown ai attacker knows its own value
			// but opponent attacker is just guessing
			int fpvalue;
			if (color == Settings.topColor)
				fpvalue = values[r+1];
			else
				fpvalue = aiValue(fp, m.getFrom());

			// An unknown attacker blocking a low ranked piece
			// is worth discovering; perhaps it is blocking
			// access to the flag.
			if (!tp.isKnown() && r <= 4
				&& destValue[color][r][m.getTo()] != DEST_VALUE_NIL)
				vm += 30;

			switch (winRank[fprank.toInt()][tprank.toInt()]) {
			case Rank.EVEN :
				assert fprank != Rank.UNKNOWN : "fprank is unknown?";
				vm += aiValue(tp, m.getTo()) - fpvalue;
				setPiece(null, m.getTo());
				break;

			case Rank.LOSES :
				assert fprank != Rank.UNKNOWN : "fprank is unknown?";
				// note: use values not aiValue for
				// attacker because attacker knows its value
				// even if attacker is unknown
				vm -= fpvalue;
				vm += makeKnown(tp);

				break;

			case Rank.WINS:
				vm += aiValue(tp, m.getTo());
				if (!tp.isKnown()) {
					// tp is not known

					// fp is known or an eight

					// encourage ai to not move
					// a piece subject to attack.
					// or attack an unmoved piece
					if (tp.moves == 0)
						vm -= fpvalue;

					// unknown pieces (except 8)
					// have bluffing value
					if (tprank != Rank.EIGHT)
						vm -= aiBluffingValue(m.getTo());
				}

				vm += makeKnown(fp);
				setPiece(fp, m.getTo()); // won
				break;

			case Rank.UNK:
			// fp or tp is unknown
			if (tp.getColor() == Settings.topColor) {
				// ai is defender (tp)
				assert fp.getRank() == Rank.UNKNOWN : "opponent piece is known?";


				if (isInvincible(tp) && !(tprank == Rank.ONE && possibleSpy && (fp.getActingRankLow() == Rank.NIL || fp.getActingRankLow() != Rank.ONE))) {
					// defender wins
					// tp stays on board
					vm -= fpvalue;
					vm += makeKnown(tp);
				} else {
					vm += aiValue(tp, m.getTo()) - fpvalue;

					// setPiece(null, to);	// even
					// this encourages the ai to keep
					// its attacked pieces next to its unknown
					// pieces, because if attacker wins
					// then it is subject to attack
					// by unknown ai pieces.
					vm += makeKnown(fp);

					// assume worst case for ai
					// defender loses
					setPiece(fp, m.getTo());	// attacker wins
				}

			} else {
				// ai is attacker (fp)
				// it may or may not be known
				// defender may or may not be known

				assert fp.getColor() == Settings.topColor : "fp is opponent?";


				if (isInvincible(fp) && tp.moves != 0) {
					vm += aiValue(tp, m.getTo());
					vm += makeKnown(fp);
					setPiece(fp, m.getTo()); // won
				} else {
					if (!tp.hasMoved() && fp.getRank().toInt() <= 4) {
						vm -= 1000;
						// assume lost
						// "from" square already cleared on board
					} else {
						vm += aiValue(tp, m.getTo()) - fpvalue; 
						vm += makeKnown(fp);
						// assume worst case for ai
						// attacker loses
					}
				}
			}
			break;
			} // switch

			// prefer early attacks for both ai and opponent
			vm -= depth;

		} // else attack

		if (fp.getColor() == Settings.topColor)
			value += vm;
		else
			value -= vm;;
		fp.moves++;
	}

        protected void moveHistory(Piece fp, Piece tp, BMove m)
        {
                if (tp != null)
                        tp = new Piece(tp);
                undoList.add(new UndoMove(new Piece(fp), tp, m.getFrom(), m.getTo()));
	}


	public void undo(int valueB)
	{
		value = valueB;
		UndoMove um = getLastMove();
		setPiece(um.getPiece(), um.getFrom());
		setPiece(um.tp, um.getTo());
		undoList.remove(undoList.size()-1);
	}
	
	// Value given to discovering an unknown piece.
	// Discovery of these pieces is the primary driver of the heuristic,
	// as it leads to discovery of the flag.

	private void aiUnknownTpValue(Piece fp, Piece tp, BMove m, int depth)
	{
		// If the defending piece has not yet moved
		// but we are evaluating moves for it,
		// assume that the defending piece loses.
		// This deters new movement of pieces near opp. pieces,	
		// which makes them easy to capture.

		// ai is attacker (fp)
		assert tp.getRank() == Rank.UNKNOWN : "tp is known?"; // defender (tp) is unknown

		// Note: Acting Rank is an unreliable predictor
		// of actual rank.
		//
		// tp actingRankHigh is very unreliable because
		// often an unknown opponent piece (such as a 1-3)
		// will eschew taking a known ai piece (such as a 5-7)
		// to avoid becoming known.
		//
		// If the tp actingRankHigh is >= fp rank
		// it is probably at least an even exchange and
		// perhaps better, so we add the entire tp value
		// and do not subtract the tp value (which we do in
		// the case of an even exchange).
		// if (tp.getActingRankHigh() != Rank.NIL
		//	&& tp.getActingRankHigh().toInt() >= fp.getRank().toInt())
		//	value += tp.aiValue();
		// else
		// if (tp.getActingRankLow() != Rank.NIL
		// 	&& tp.getActingRankLow().toInt() <= fp.getRank().toInt())
		// 	value += fp.aiValue();
		// else
		//	value += tp.aiValue() + fp.aiValue();

		if (!tp.hasMoved() && fp.getRank().toInt() <= 4
			|| fp.getRank() == Rank.EIGHT) {
			value += -1000;
			// assume lost
			// "from" square already cleared on board
		} else
			value += aiValue(tp, m.getTo());

		// } else if (depth != 0 && fp.moves == 1 && !fp.isKnown() && !isInvincible(tp)) {
			// "from" square already cleared on board
		// 	aiBluffingValue(to);
		// 	setPiece(null, to);	// assume even
		// }
			// else assume lost
			// "from" square already cleared on board
	}


	private void aiUnknownFpValue(Piece fp, Piece tp, BMove m)
	{
		// ai is defender (tp)
		assert fp.getRank() == Rank.UNKNOWN : "fp is known?"; // attacker (fp) is unknown
		// encourage ai to not move an unmoved piece
		// that is subject to attack.
		// if (tp.moves == 0 && !tp.isKnown())
		// 	value += aiValue(fp, m.getFrom());
		// else
			value += aiValue(tp, m.getTo());

		// if (!fp.hasMoved()) {
			// attacker loses
			// "from" square already cleared on board
		// Unknown but moved ai pieces have some bluffing value.
		// Unknown Eights and Spys are usually so obviously
		// trying to get at bombs and Marshals
		// that they have no bluffing value.
		// } else if (tp.hasMoved() && !tp.isKnown() && !isInvincible(fp) && tp.getRank() != Rank.EIGHT) {
			// bluffing value
		// 	aiBluffingValue(to);
		// } else
			// setPiece(null, to);	// even
			// this encourages the ai to keep
			// its attacked pieces next to its unknown
			// pieces, because if attacker wins
			// then it is subject to attack
			// by unknown ai pieces.
			setPiece(fp, m.getTo());	// attacker wins
	}

	// if the prior move was to the target square,
	// then the opponent must consider whether the ai is bluffing.
	// This could occur if the opponent moves a piece next to an
	// unknown ai piece or if the ai moved its unknown piece next to an
	// opponent piece.
	//
	// Either way,add bluffing value.
	//
	// However, this gets stale after the next move, because if the ai
	// does not attack, it probably means the ai is less strong,
	// and the opponent will know it.
	public int aiBluffingValue(int to)
	{
		UndoMove prev = getLastMove();
		if (to == prev.getTo())
			return 30;
		return 0;
	}

	public int getValue()
	{
		return value;
	}

	public void addValue(int v)
	{
		value += v;
	}

	public void setValue(int v)
	{
		value = v;
	}


	// Stealth value is expressed as a percentage of the piece's
	// actual value.  An unknown Marshal should not take a Six
	// or even a Five, but certainly a Four is worthwhile.
	// An unknown General should not take a Six, but a Five
	// would be tempting.
	//
	// So maybe stealth value is about 11%.
	//
	// If a piece has moved, then much is probably guessed about
	// the piece already, so it has half of its stealth value.
	//
	private int stealthValue(Rank r)
	{
		return values[r.toInt()] / 9;
	}

	private int makeKnown(Piece p)
	{
		if (!p.isKnown()) {
			p.setKnown(true);
			return -stealthValue(p.getRank());
		}
		return 0;
	}

	// If a piece has already moved, then
	// the piece cannot be a flag or a bomb,
	// so the attacker doesn't gain as much info
	// by discovering it.  (The ai scans for
	// for unmoved piece patterns as a part
	// of its flag discovery routine).

	// If the rank is needed, the penalty is reduced
	// so that the ai will move the piece if it can make progress
	// towards its destination.

	// The difference in value between a moved piece and
	// an unmoved unknown piece is what drives the ai to attack
	// unmoved unknown pieces.

	private int aiMovedValue(Piece p)
	{
		Rank r = p.getRank();
		if (neededRank[p.getColor()][r.toInt()-1])
			return 1;
		else
			return values[Rank.UNKNOWN.toInt()] / 4;
	}

	// De Boer (2007) suggested a formula
	// for the relative values of pieces in Stratego:
	// • The Flag has the highest value of all
	// • The Spy’s value is half of the value of the Marshal
	// • If the opponent has the Spy in play,
	//	the value of the Marshal is multiplied with 0.8
	// • When the player has less than three Miners,
	//	the value of the Miners is multiplied with 4 − #left.
	// • The same holds for the Scouts
	// • Bombs have half the value of the strongest piece
	//	of the opponent on the board
	// • The value of every piece is incremented with 1/#left
	//	to increase their importance when only a few are left
	//
	// A.F.C Arts (2010) used the following value relations:
	// • First feature is multiplying the value of the Marshal
	//	(both player and opponent) with 0.8 if the
	//	opponent has a Spy on the game board.
	// • Second feature multiplies the value of the Miners with 4 − #left
	//	if the number of Miners is less than three.
	// • Third feature sets the value of the Bomb
	//	to half the value of the piece with the highest value.
	// • Fourth feature sets divides the value of the Marshal by two
	//	if the opponent has a Spy on the board.
	// • Fifth feature gives a penalty to pieces
	//	that are known to the opponent
	// • Sixth feature increases the value of a piece
	//	when the player has a more pieces of the same type
	//	than the opponent.
	//
	// Eventually the value of a piece is multiplied
	//	with the number of times that the piece is on the board,
	//	and summated over all the pieces.
	// Values are based upon the M.Sc. thesis by Stengard (2006).
	//
	// The following implementation is similar, but differs:
	// • Bombs are worthless, except if they surround a flag or
	//	block lanes.
	// • The value of the Spy becomes equal to a Seven once the
	//	opponent Marshal is removed.

	private static int aiBombValue()
	{
		return 100;
	}


	private int aiValue(Piece p, int i)
	{
		// piece value (bomb, flag) overrides calculated value
		int v = p.aiValue();

		if (v == 0) {
			Rank r = p.getRank();

			if (p.isKnown()) {
				v = values[r.toInt()];

				// demote the value of the spy if there is
				// no opponent marshal left at large
				if (r == Rank.SPY && !rankAtLarge(1-p.getColor(), 1))
					v = values[Rank.SEVEN.toInt()];
					
			} else {
				Random rnd = new Random();
				v = values[Rank.UNKNOWN.toInt()]
					+ stealthValue(r)

				// discourage stalling
				// TBD: stalling factor should only
				// apply in repetitive cases,
				// not generally.  Otherwise, ai
				// makes bad decisions when it
				// is actually making progress.
				// + recentMoves.size()/10

				// we add a random 1 point to the value so that
				// if the ai has a choice of attacking two or more unknown
				// pieces during a series of attacks,
				// it will alternate direction.
				+ rnd.nextInt(2);

				// unknown pieces are worth more on the back ranks
				// because we need to find bombs around the flag
				// note: *3 to outgain -depth late capture penalty

				if (p.getColor() == Settings.bottomColor) {
					if (!p.hasMoved())
						v += (i/11-7) * 3;
				} else {
					if (!p.hasMoved())
						v += (4-i/11) * 3;
				}

				if (p.moves != 0)
					v -= aiMovedValue(p);

				// If a moved piece is blocking an
				// ai piece because the ai thinks it could
				// be a high piece, the ai needs to call the
				// bluff.  So pieces that are acting as low
				// ranking pieces (1 - 4) need to be discovered.
				if (p.getActingRankLow().toInt() <= 4)
					v += 10;

			} // piece not known
		} // v == 0

		return v;
	}

}
