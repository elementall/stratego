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

package com.cjmalloy.stratego.player;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

import com.cjmalloy.stratego.Board;
import com.cjmalloy.stratego.Grid;
import com.cjmalloy.stratego.Move;
import com.cjmalloy.stratego.UndoMove;
import com.cjmalloy.stratego.Piece;
import com.cjmalloy.stratego.Rank;
import com.cjmalloy.stratego.Spot;
import com.cjmalloy.stratego.Settings;
import java.util.Random;



public class TestingBoard extends Board
{
	private static final int DEST_PRIORITY_DEFEND_FLAG = 10;
	private static final int DEST_PRIORITY_DEFEND_FLAG_BOMBS = 6;
	private static final int DEST_PRIORITY_DEFEND_FLAG_AREA = 5;


	// Note: DEST_PRIORITY_ATTACK_FLAG should be higher than
	// DEST_PRIORITY_CHASE, because an Eight could be
	// an expendable piece and can chase other opponent pieces,
	// but it is better for the eight to attack the flag structure.

	private static final int DEST_PRIORITY_ATTACK_FLAG = 4;
	private static final int DEST_PRIORITY_CHASE_ATTACK = 4;
	private static final int DEST_PRIORITY_CHASE_DEFEND = 3;

	// Note: DEST_PRIORITY_LANE must have unique priority
	// because it is a special case in planv.  It is limited to
	// the lanes and has priority over CHASE, but not over CHASE_ATTACK
	// or CHASE_DEFEND which are used by invincible pieces.

	private static final int DEST_PRIORITY_LANE = 2;
	private static final int DEST_PRIORITY_CHASE = 1;
	private static final int DEST_PRIORITY_LOW = 1;

	private static final int DEST_VALUE_NIL = 9999;
	private static final int GUARDED_OPEN = 0;
	private static final int GUARDED_UNKNOWN = 1;
	private static final int GUARDED_OPEN_CAUTIOUS = 2;
	private static final int GUARDED_CAUTIOUS = 3;
	private static final int VALUE_BLUFF = 2;	// negative discourages bluffing

	protected Piece[][] planAPiece = new Piece[2][15];	// moved rank Piece
	protected Piece[][] planBPiece = new Piece[2][15];	// unmoved rank Piece
	protected boolean[][] neededRank = new boolean[2][15];	// needed ranks
	protected int[][][][] planA = new int[2][15][2][121];	// plan A
	protected int[][][][] planB = new int[2][15][2][121];	// plan B
	protected int[] sumValues = new int[2];
	protected int value;	// value of board
	protected int[] unmovedValue = new int[121];    // unmoved value
	protected int[][] valueStealth = new int[2][15];
	protected static final int attacklanes[][] = { { 78, 79}, { 82, 83}, {86, 87} };
	protected long[] hashTest = new long [2];
	protected int lowestUnknownNotSuspectedRank;
	protected int lowestUnknownExpendableRank;
	protected int[] nUnknownExpendableRankAtLarge = new int[2];
	protected int dangerousKnownRank;
	protected int dangerousUnknownRank;
	protected Random rnd = new Random();
	protected UndoMove lastMove;
	protected boolean knownAIOne;
	protected int[] unknownRank = new int[2];
	public int depth = -1;
	protected Piece lastMovedPiece;

// Silly Java warning:
// Java won't let you declare a typed list array like
// public ArrayList<Piece>[] scouts = new ArrayList<Piece>()[2];
// and then it warns if you created a non-typed list array.
@SuppressWarnings("unchecked")
	public ArrayList<Piece>[] scouts = (ArrayList<Piece>[])new ArrayList[2];

	protected boolean[] foray = new boolean[15];
	protected static boolean forayMap[] = new boolean[121];
	protected static int forayLane = -1;

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
	//
	// In this implementation:
	// • Each of the lowest ranked pieces (1-4) is worth two of
	//	the next lowest rank.  (While this may not be accurate
	//	for competitive human play, it seems to hold true
	//	for AI play, because the loss of any of its low ranking
	//	pieces means almost certain loss once the remaining
	//	pieces are traded off.)
	// • If the opponent has the Spy in play,
	//	the value of the Marshal is multiplied by 0.9.
	// • An unknown piece is worth more than a known piece (stealth).
	// • If a player no longer has any pieces of rank N
	//	and rank N-1, the value of the opponent's rank N-1 becomes
	//	equal to Math.min(value(N), value(N-1)), because pieces of
	//	both rank N and N-1 have the same strength.
	// • Bombs are worthless, except if they surround a flag.
	//	These bombs are worth a little more than a Miner.
	// • An unsuspected Spy has a value between a Colonel and a
	//	Major.  It is obviously worth more than a Major, which
	//	can be lost and still the game can be won.  But
	//	losing a Three makes it much harder for the AI to win.
	//	However, once the Spy is suspected,
	//	it has limited value except in certain reduced piece
	//	endgames with Ones.
	// • The Nine is the next lowest valued piece on the board.  However,
	//	its stealth value is highest for ranks Six through Nine
	//	(stealth value increases by 3 points for each of
	//	these ranks).  High stealth value encourages the Nine
	//	to maintain its stealth by moving only square at a time.
	//	An unmoved Nine should attack a suspected One, Two or
	//	or Three, so its combined value (value + unmoved state
	//	+ stealth) must be less than the stealth of the attacked
	//	low ranked piece.  A known Nine should attack a suspected
	//	Four.
	// • The value of the Miner depends on the number of structures
	//	left than could surround a flag.
	//	- When the opposing player has more structures left than Miners
	//	  the value of the Miners is increased.
	//	- When the opposing player has less structures left than Miners
	//	  the value of the excess Miners becomes equal to a Seven.
	// • If the AI is winning, an AI piece is worth less than
	//	the opponents piece of the same rank.  If the AI is losing,
	//	its pieces are worth more.
	// • An unbombed flag is worth about a known Three.  (It is not
	//	the highest value because of the opponent never really
	//	knows for sure the location of the flag.  See the code
	//	for more information).
	// 
	// Note that known Sevens and Nines have very little value,
	// and the AI should sacrifice these pieces by approaching
	// unknown opponent pieces, because the possibility of gaining
	// almost any discovery exceeds their value.  If the AI is
	// winning (and therefore its pieces are reduced in value)
	// a known Seven (or Nine) should sacrifice itself
	// to discover a Four (40 stealth)
	// but not a Five (16 stealth).  

	private static final int VALUE_THREE = 400;
	private static final int VALUE_FOUR = 200;
	private static final int VALUE_FIVE = 100;
	private static final int VALUE_SIX = 50;
	private static final int VALUE_SEVEN = 36;
	private static final int VALUE_EIGHT = 30;	// variable
	private static final int VALUE_NINE = 20;
	private static final int VALUE_SPY=(VALUE_THREE + VALUE_FOUR)/2;
	private static final int [] startValues = {
		0,
		1600,	// 1 Marshal
		800, 	// 2 General
		VALUE_THREE, 	// 3 Colonel
		VALUE_FOUR,	// 4 Major
		VALUE_FIVE,	// 5 Captain
		VALUE_SIX,	// 6 Lieutenant
		VALUE_SEVEN,	// 7 Sergeant
		VALUE_EIGHT,	// 8 Miner
		VALUE_NINE,	// 9 Scout
		VALUE_SPY,	// Spy value with opponent One still on board
		0,	// Bomb (valued by code)
		1000,	// Flag (valued by code)
		0,	// Unknown (valued by code, minimum piece value)
		0	// Nil
	};
	public int [][] values = new int[2][15];

	// If a piece has already moved, then
	// the piece cannot be a flag or a bomb,
	// so the attacker doesn't gain as much info
	// by discovering it.  (The ai scans for
	// for unmoved piece patterns as a part
	// of its flag discovery routine).

	private static final int VALUE_MOVED = 5;

	// cache the winFight values for faster evaluation
	protected static int[][] winRank = new int[15][15]; // winfight cache
	static {
		for (Rank frank : Rank.values())
		for (Rank trank : Rank.values())
			winRank[frank.ordinal()][trank.ordinal()]
				= frank.winFight(trank);
	}

	public TestingBoard() {}
	
	public TestingBoard(Board t)
	{
		super(t);

		value = 0;
		hashTest[0] = boardHistory[0].hash;	// for debugging (see move)
		hashTest[1] = boardHistory[1].hash;	// for debugging (see move)

		for (int c = RED; c <= BLUE; c++) {
			for (int j=0;j<15;j++) {
				planAPiece[c][j] = null;
				planBPiece[c][j] = null;
				neededRank[c][j] = false;
				values[c][j] = startValues[j];
				valueStealth[c][j] = 0;
			}
		} // color c

		for (int i=12;i<=120;i++) {
			unmovedValue[i] = 0;
			if (!Grid.isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p != null) {

		// Make a copy of the piece because the graphics
		// thread is also using board/piece information for display

				Piece np = new Piece(p);
				grid.setPiece(i, np);
				np.setAiValue(0);

				Rank rank = p.getRank();
				int r = rank.ordinal();

		// only one piece of a rank is assigned plan A
		// and preferably a piece that has moved or is known and as
		// far forward on the board as possible

				if (p.isKnown()) {
					if (hasPlan(planAPiece, p.getColor(), r))
						planBPiece[p.getColor()][r-1]=planAPiece[p.getColor()][r-1];
					planAPiece[p.getColor()][r-1]=np;
				} else if (p.hasMoved()) {
					if (!hasPlan(planAPiece, p.getColor(), r))
						planAPiece[p.getColor()][r-1]=np;
					else {
						if (!planAPiece[p.getColor()][r-1].isKnown()) {
							planAPiece[p.getColor()][r-1]=np;
							planBPiece[p.getColor()][r-1]=planAPiece[p.getColor()][r-1];
						} else
							planBPiece[p.getColor()][r-1]=np;
					}
				} else
					planBPiece[p.getColor()][r-1]=np;
			}
		}

		// call genSuspectedRank early before calling aiValue()
		// but after trayRank and knownRank are calculated
		// because genSuspectedRank depends on unknownRankAtLarge()
		genSuspectedRank();
		genUnknownExpendableRankAtLarge();
		adjustPieceValues();
		genDangerousRanks();
		genForay();	// depends on sumValues, dangerousUnknownRank

		// Destination Value Matrices
		//
		// Note: at depths >8 moves (16 ply),
		// these matrices may not necessary
		// because these pieces will find their targets in the 
		// move tree.  However, they would still be useful in pruning
		// the move tree.
		for (int c = RED; c <= BLUE; c++)
		for (int rank = 0; rank < 15; rank++) {
			planA[c][rank][0][0] = 0;
			for (int j=12; j <= 120; j++) {
				planA[c][rank][0][j] = DEST_VALUE_NIL;
				planA[c][rank][1][j] = 0;
				planB[c][rank][0][j] = DEST_VALUE_NIL;
				planB[c][rank][1][j] = 0;
			}
		}

		// valuePieces should be called after all individual
		// piece values have been determined.
		// Destination Value Matrices depends on piece values
		// so that needs to be called later.

		valuePieces();
		genValueStealth();	// depends on valuePieces
		genInvincibleRank();	// depends on stealth
		genDestFlag();
		aiFlagSafety(); // depends on genDestFlag, valueStealth, values

		scouts[0] = new ArrayList<Piece>();
		scouts[1] = new ArrayList<Piece>();
		for (int i=12;i<=120;i++) {
			if (!Grid.isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p == null)
				continue;
			Rank rank = p.getRank();
			if (rank == Rank.BOMB || rank == Rank.FLAG)
				continue;

			else if (rank == Rank.ONE
				&& p.getColor() == Settings.topColor)
				knownAIOne = p.isKnown();

			else if (rank == Rank.NINE
				|| (unknownRankAtLarge(p.getColor(), Rank.NINE) != 0
					&& rank == Rank.UNKNOWN))
				scouts[p.getColor()].add(p);

		// Encourage lower ranked pieces to find pieces
		// of higher ranks.
		//
		// Note: this is a questionable heuristic
		// because the ai doesn't know what to do once
		// the piece reaches its destination.
		// However, the position can evolve into one
		// where material can be gained.
			chase(p);

		}
		attackLanes();

		// keep a piece of a high rank in motion,
		// preferably a 6, 7 or 9 to discover unknown pieces.
		// 5s are also drawn towards unknown pieces, but they
		// are more skittish and therefore blocked by moving
		// unknown pieces.
		needExpendableRank(Settings.topColor);

		// setunmovedValues depends on neededRank:
		// chase(), needExpendableRank()
		setUnmovedValues();

		// targetUnknownBlockers();

		assert flag[Settings.topColor] != null : "AI flag unknown";
	}

	int missingValue(int c, int r)
	{
		if (r == 10 || rankAtLarge(c, r) != 0)
			return values[1-c][r-1];
		return Math.min(values[1-c][r-1], missingValue(c, r+1));
	}

	void adjustPieceValues()
	{
		for (int c = RED; c <= BLUE; c++) {

		// If a player no longer has any pieces of rank N
		// and rank N-1, the value of the opponent's rank N-1 becomes
		// equal to Math.min(value(N), value(N-1)), because pieces of
		// both rank N and N-1 have the same strength.
		//
		// The rank value is set to the lowest value of any of the ranks.
		//
		// Example 1. Blue has a 2, 5, 8, 9.  Red has a 2, 4, 9, 9.
		// Red 3 value is the same as Red 4 value.
		// Blue 5,6,7 value is the same as Blue 7 value.
		//
		// Example 2.  Blue has 5, 8, 9.  Red has 4 4 5 6.
		// Red 5,6 value is the same as Red 7.
		//
		// Example 3.  Blue has 1, S, 6, 7.  Red has 1, S, 2, 5, 8.
		// The value of Red One is 3/4 of Red One original value.
		// Blue 6 value is the same as Blue 7.
		//
		// Example 4.  Blue has S, 6, 7.  Red has 1, 4, 8.
		// Red 2,3,4 value is the same as Red 5.
		// The value of Red One is 3/4 of the value of
		// Red Five.  This makes
		// Red Four the most valuable piece on the board.
		//
		// Example 5.  Blue has 1, 6, 7.  Red has 1, S, 2, 5, 8.
		// Should Red One exchange with Blue One, even though
		// Blue one is worth less because Red has the Spy?
		// The exchange would create an invincible Win Rank, but
		// this needs more thought.
		//
		// a One is worth less if the opponent
		// has a Spy.  (This is a big reason why the Spy
		// is highly valued). 
		
		// (Note that percentage is used rather than subtracting
		// some absolute value.  That is because the value
		// of unmatched invincible pieces are set to the
		// value of the next higher rank.)

		// Note: a One can be worth *less* than a
		// Two (or even higher) rank if the opponent
		// no longer has any lower ranks but still has
		// a Spy.

			for (int r = 1; r <= 7; r++)
				if (rankAtLarge(c, r) == 0)
					values[1-c][r] = missingValue(c, r+1);
						
		// The value of a One is worth somewhat less if the opponent
		// still has the Spy.  This encourages the One to trade
		// with an opponent One if the player does not have the Spy,
		// making the opponent One more valuable.  However, the
		// difference in value must not be more than the stealth.
		// Otherwise the AI would exchange its unknown One for
		// a known One, which could easily lead to the loss of
		// the rest of its pieces, if the loss of its One made
		// some of the opponent's ranks invincible.
		// By reducing the value to 90%, the One loses 80 points,
		// which is less than the stealth value.
		// The AI will sacrifice a Five but not a Four to
		// exchange known Ones, where the opponent has a Spy but
		// the AI doesn't.
		//
		// TBD: If the opponent Spy is suspected, then the value
		// of the player One is hardly affected, as the Spy
		// is easily avoided.

			if (hasSpy(1-c))
				values[c][1] = values[c][1]*9/10;

		// If the opponent Marshal is off on the board,
		// the Spy value is reduced.

			if (rankAtLarge(1-c, Rank.ONE) == 0)
				values[c][10] = values[c][7]/2;

		// The opponent spy, once suspected, is worth maybe a Five, but
		// this depends on the remaining pieces, because in an endgame with
		// Ones on the board, the Spy can be the critical piece to win the game.

			else if (c == Settings.bottomColor)
				values[c][10] = VALUE_FIVE; // TBD: depends on remaining pieces

		// Do invincible pieces have higher value than
		// non-invincible pieces?
		//
		// If invincible pieces exchange equally,
		// the next higher rank becomes invincible.
		// For example, both sides have a One, Three, and a Four.
		// Red has an unknown One and Blue has a known One,
		// making Red Three invincible.  If Red and Blue exchange
		// Threes, then Red's Four becomes invincible.
		//
		// If Red did not have a Four, then the exchange would
		// benefit Blue.  But then Red will eschew the
		// exchange anyway, because its pieces are more valuable
		// due to the lack of a Four.
		//
		// Therefore, invincible pieces do not have a higher value.

		// Sum the values of the remaining piece ranks.
		// This is used to determine whether the AI is winning.
		// If the AI is winning, it seeks exchanges as a
		// way to enter the endgame with superior ranks.
		// Thus, Eights, Nines and the Spy have little value
		// in this analysis, because it is the superior pieces
		// that count.  Yet these pieces have some value
		// in the endgame and in their ability to pose as other pieces.
		//
		// TBD: This formula fails towards the end of the game,
		// when the number of pieces remaining dominates.
		// Still, it is not clear which combination of
		// pieces and known/unknown status create winning endgames.
		// As such, this would have general
		// applicability to the game of stratego, so someone should
		// analyze the endgames and publish a FAQ.
		//
		// Note: genDestFlag() depends on sumValues.
		// Note: genDestFlag() revalues Eights.
		// 
			sumValues[c] = 0;
			for (int rank = 1; rank <= 10; rank++) {
				if (rank >= 8) {
					int n = unknownRankAtLarge(c, rank);
					sumValues[c] += (11 - rank) * 10 * n;
					n = knownRankAtLarge(c, rank);
					sumValues[c] += 10 * n;
				} else {
					int n = rankAtLarge(c, rank);
					sumValues[c] += values[c][rank] * n;
				}
			}

		} // color
	}

	void genUnknownExpendableRankAtLarge()
	{
		// The number of expendable ranks still at large
		// determines the risk of discovery of low ranked pieces.
		// If there are few expendable pieces remaining,
		// the AI can be more aggressive with its unknown low ranked
		// pieces.
		for (int c = RED; c <= BLUE; c++) {
			nUnknownExpendableRankAtLarge[c] = 0;
			for (int r = 5; r <= 9; r++)
				nUnknownExpendableRankAtLarge[c] += unknownRankAtLarge(c, r);
		}
	}

	void genDangerousRanks()
	{
		lowestUnknownNotSuspectedRank = invincibleRankInt[Settings.topColor];

		// dangerousUnknownRank is set when an opponent
		// has an invincible unknown rank.  This increases
		// the risk of loss (see riskOfLoss()) when opponent
		// pieces approach AI pieces.
		// Normally, the AI assumes that the opponent
		// will not subject its unknown low ranked pieces to attack.
		// But if the opponent has an invincible unknown rank,
		// an unknown opponent piece might approach an unknown AI piece
		// because it only risks discovery rather than complete
		// loss of its piece.  

		dangerousUnknownRank = 99;
		dangerousKnownRank = 99;
		for (int rank = 1; rank <= invincibleRankInt[Settings.bottomColor]; rank++)
			if ((rank == 1 && !hasSpy(Settings.topColor))
				|| rank != 1) {
				if (unknownRankAtLarge(Settings.bottomColor, rank) != 0
					&& dangerousUnknownRank == 99)
					dangerousUnknownRank = rank;
				if (rankAtLarge(Settings.bottomColor, rank) != 0
					&& dangerousKnownRank == 99)
					dangerousKnownRank = rank;

			}
	}

	void needExpendableRank(int c)
	{
		// if player is not winning, it is best to let
		// opponent attack and try for a draw

		if (isWinning(c) < 0)
			return;

		int count = 0;
		for (int i=12;i<=120;i++) {
			Piece p = getPiece(i);
			if (p == null || p.getColor() != c)
				continue;
			if (!isExpendable(p))
				continue;
			if (!p.isKnown() && p.hasMoved())
				count+=2;
			else if (p.isKnown())
				count++;
		}

		if (count >= 4)
			return;

		// if there are not any high rank pieces in motion
		// set the neededRank.  The search tree will
		// move the rank that can make the most progress.
		for (int r : expendableRank)
			setNeededRank(c,r);
	}

	// Perhaps the key and most complex decision in Stratego is whether
	// to lose stealth and capture a piece.  The decision is based on the
	// probability of capturing a yet lower ranked piece or preventing
	// the capture of one of its lower ranked pieces by maintaining
	// continued stealth.
	//
	// For example, an unknown Marshal should not take a Four
	// (if the opponent still has a Two and two Threes),
	// but certainly a Three is tempting.
	// An unknown General should not take a Five, but a Four
	// would be tempting.
	//
	// I have seesawed on whether an unknown Marshal should take a Three.
	// If it takes a Three, it makes the opponent Two invincible.
	// But if doesn't take a Three, then the opponent Threes
	// become defacto invincible once the Two is known.
	//
	// Currently, an unknown AI One will attack a Three
	// only if any of the Twos or any of the Threes
	// are gone from the board.  Otherwise, the AI One will only
	// pursue the Two, ignoring the opponent Threes.
	//
	// An unknown General will attack a Four only if a couple of
	// Threes are gone from the board.  (If the opponent makes a foray
	// with a Four, the AI relies on the fact that it has 2 Threes
	// and 3 Fours to repulse the attack).
	//
	// Stealth value for pieces (1-4) is slightly less than
	// the sum of next six higher ranked pieces still on the board
	// times a risk factor if stealth is maintained.
	//
	// The risk factor is 10%, which is the probability
	// of capture of each of these pieces.
	//
	// If the piece is the only player piece that prevents an opponent
	// piece from becoming a dangerous rank, the value is increased
	// by 1/3.  If there are more than one player pieces, the value is
	// decreased by 1/3.
	//
	// Stealth of a low ranked piece decreases the less chance
	// it has for capture a less valuable piece.
	// For example, if the Ones are still on the board, the
	// Twos have been removed, the stealth of the One
	// drops in half.
	//
	// A One stealth value is equal to 3200 *.1 (320 +- 106 points)
	// if both Twos and all the Threes are still on the board.
	//
	// A Two stealth value is equal to 1000 *.1 (200 +- 66 points)
	// if all Threes and two of the Fours are still on the board.
	//
	// A Three stealth value is equal to 1200 *.1 (120 +- 40 points)
	// if six Fours are still on the board.
	//
	// A Four stealth value is equal to 600 * .1 (60 +- 20 points)
	// if six Fives are still on the board.
	//
	// Stealth value for pieces (6-9) derives not from opponent piece value,
	// but from opponent stealth value and bluffing.
	// Stealth value increases with increasing rank.  For example,
	// a known Nine has little value, particularly if the opponent
	// no longer has any Nines, because it almost always loses any attack.
	// Therefore its only value is the stealth value of an opponent piece.
	// An unknown Nine has this value AND the ability to bluff.
	//
	// In the example below, Red Six and Red Seven are unknown
	// and Blue Nine is known.  Red has a choice between R6xB9 and R7xB9.
	// Because the stealth value of a Seven is higher than a Six,
	// Red should play R6xB9.  Thus the Seven remains cloaked and
	// can bluff whereas known Red Six is slightly more of a deterrent
	// (and bait) to opponent pieces than a Red Seven.
	// R6 B9 R7
	//
	// Thus, the AI intends to keep its lower ranked and higher ranked
	// pieces cloaked while using its middle pieces for fighting.
	// Stealth value for 5-9 is:
	// Five 10
	// Six 5
	// Seven 8
	// Eight 11
	// Nine 14
	//
	// If a piece is invincible without compare (it always wins
	// any attack), it has no stealth, because we want to encourage
	// the AI to use it to clean up the last remaining opponent
	// pieces on the board.
	//
	// Stealth value of opponent pieces is calculated somewhat
	// differently.  One can argue that because the ai
	// already has guessed the rank, that the piece would have
	// less stealth value, but because the ai cannot
	// rely on the guessed rank and because of the importance
	// of identifying low ranked pieces accurately, stealth
	// is still very important.
	//
	// The AI should not risk its more valuable pieces on 
	// just to discover the actual rank of an opponent piece.
	// For example, if Red Four has a chance to attack an unknown
	// Blue that it suspects is a One, it should flee and leave
	// the discovery to a lesser piece.
	//
	// The important ranks to discover are Ones, Twos and Threes.
	// Often, these suspected ranks can be confused, so there is not
	// much difference in stealth between them.
	// So for an opponent rank (1-3), stealth is:
	// One 60
	// Two 40
	// Three 35	// must be at least 35 so unknown ranks 7-9 will attack
	// Four 15
	//
	// For example,
	// B? B5 R4
	// The AI should not play R4XB5 if B? is a suspected Two, because the
	// value of a Four (100) > Five (50) + Two Stealth (40).
	// (This is the same if B? is unknown, see unknownValue())
	//
	// For example,
	// B? B6 R5
	// The AI should not play R5XB6 if B? is a suspected Four, because the
	// value of a Five (50) > Six (30) + Four Stealth (15).
	//
	// (In the same example, if B? is unknown, the AI would play R5XR6,
	// because the value of a Five (50) > Six (30) + Three Stealth (35).
	// This can happens if B6 is protected by more than one piece,
	// so the protectors do not obtain a suspected rank.
	// But unknownValue() will reduce the value for multiple protectors.)
	//
	// This means that higher ranked pieces (6,7,9) will be eager
	// to sacrifice themselves to discover ranks (1-2) and the
	// ranks (7,9) are eager to discover a Three.
	//
	// Another area where stealth values are important is in
	// unknownValue().  If an AI Four has a choice between
	// an exchange with a suspected rank of Three or an Unknown,
	// the difference is the Three stealth or Two stealth, so
	// the Four will be inclined to take its chances with the Unknown.
	//

	private void genValueStealth()
	{
		for (int c = RED; c <= BLUE; c++) {
		for (int r = 1; r <= 10; r++) {
		int v = 0;

		// Five stealth should be lower than the value of a Six (25)
		// minus a Seven (16).  Otherwise, a Six will attack
		// a Seven protected by a suspected Five.
		// This is basically an even exchange.
		// (A Six should always attack a Seven protected by an
		// unknown piece).

		if (r == 5)
			v = values[c][5]/6;
		else if (r >= 5 && r <= 9) {
			final int stealthRatio[] = {0, 0, 0, 0, 0, 6, 5, 2, 2, 1};
			v = values[c][r]/stealthRatio[r];

		// Eight stealth is higher when there is still a
		// bombed structure on the board because it is easier
		// for an unknown Eight to approach than a known Eight,
		// given that the player has multiple unknown pieces.

			if (r == 8
				&& maybe_count[1-c] - open_count[1-c] != 0)
				v = 50;
		}

		// Spy stealth depends on whether the Spy is
		// suspected.  If the location of the Spy has not been guessed,
		// its value is about a Colonel.  But once it is suspected,
		// its value is worth much less.  Because the value
		// of an opponent Spy only is used if the AI has
		// suspected its location, this is the only value
		// for the opponent Spy.  But the AI doesn't know
		// if the opponent has guessed its Spy, so the only
		// value is a high value.

		else if (r == 10) {

		// Spy stealth is equal to about a Seven stealth.

			v = valueStealth[c][6];
		}
		
		// If the player is winning by more than the value
		// of a non-invincible rank, keep valueStealth of (1-4) at zero
		// to encourage reduction of pieces.

		else if (isWinning(c) > values[c][r]
			&& r > invincibleRankInt[1-c])
			v = 0;

		else {
			int n = 0;
			int unknownDefenders = 0;
			int count=6;
			boolean found = false;
			for (int rs = 1; rs<8; rs++) {
				if (rs > r) {
					n = rankAtLarge(1-c, rs);
					if (n != 0) {
						n = Math.min(n, count);
						v += values[1-c][rs] * n;
						count -= n;
						if (count == 0)
							break;
						found = true;
					}

					n = rankAtLarge(c, rs);
					if (n != 0) {
						n = Math.min(n, count);
						v += values[c][rs] * n;
						count -= n;
						if (count == 0)
							break;
					}
				}
				if (!found)
					unknownDefenders += unknownRankAtLarge(c, rs);
			}

		// note: if n is zero, then either there are no higher
		// ranks (v = 0) and stealth will be zero
		// or just one higher rank (v = higher rank value).

			if (unknownDefenders <= 1)
				v += v/3;

		// If there is more than one equal or lower ranked unknown
		// defender, then it is safe for the invincible piece to
		// attack, because the other lower ranked unknown invincibles
		// still discourage a counter-attack by the enemy.

		// If the rank is the lowest invincible, keep it cloaked.
		// For example, Red has an
		// unknown One, two known Threes, and an unknown Four.
		// Blue has a known Two and an unknown Four.
		// The unknown One should remain cloaked to
		// negate the power of the opponent Two, but the unknown
		// Four should be encouraged to run amok.

		// This should encourage an invincible piece to slam into
		// unknown pieces.

			else if (isInvincible(c, r))
				v = 10 - r;	// less than minimum piece val
			else if (unknownDefenders > 3)
				v -= v/3;
			v = v / 10;

			if (c == Settings.bottomColor) {

		// If the opponent has a dangerous unknown rank,
		// and the AI suspects which piece it is,
		// it is best to keep it unknown, because once it becomes
		// discovered, it will certainly go on a rampage.

				if (dangerousUnknownRank != 99
					&& r <= dangerousUnknownRank)
					v = 0;

		// Prior to version 9.9, opponent stealth remained constant
		// during the game; it did not depend on remaining ranks
		// at large like player stealth does.  This lead to the AI
		// attacking the opponent suspected One with an invincible
		// piece such as a Four or Five, when it would have been much
		// better for the invincible piece to attack other unknowns
		// rather than confirm the identity of the One.  blufferRisk
		// helps to reduce this problem, but it does not eliminate it.
		// So opponent stealth needs to reduced like player stealth
		// as the game wears on and it becomes obvious which pieces
		// are strong and which are weak.
		//
		// Initial values
		// 1: 120
		// 2: 84
		// 3: 48
		// 4: 36
		//
		// Note: Stealth values are modified based on movable piece
		// count (see below)

				v = (int)Math.sqrt(v);
				v *= blufferRisk * 3 / 2;
			}
		}

		valueStealth[c][r-1] = v;

		} // rank

		// Bombs not surrounding the flag have no value,
		// but they do have stealth value, proportional to
		// the number of unknown bombs remaining.
		// Because if the player discovers all the bombs,
		// then all the remaining pieces are at risk.

		valueStealth[c][Rank.BOMB.ordinal()-1] =
			(6 - unknownRankAtLarge(c, Rank.BOMB))*5;

		// Find the unknown rank with the minimum piece value
		// (value + stealth)

			unknownRank[c] = Rank.UNKNOWN.ordinal();
			assert values[c][unknownRank[c]] == 0 : "Unknown has value?";
			for (int rank = 1; rank <= 10; rank++)
				if (unknownRankAtLarge(c, rank) != 0
					&& (unknownRank[c] == Rank.UNKNOWN.ordinal()
						|| pieceValue(c,rank) < pieceValue(c,unknownRank[c])))
					unknownRank[c] = rank;

		} // color

		// If a player has a movable piece count majority, excess
		// expendable pieces just get in the way.
		// The player has the luxury of attack
		// for the sake of random discovery.
		// So increase the stealth value of the opponent pieces
		// to encourage discovery.
		int u = grid.movablePieceCount(Settings.topColor) -
			grid.movablePieceCount(Settings.bottomColor);
		u = Math.min(Math.abs(u/2), 5);

		int c;
		if (u > 0)
			c = Settings.bottomColor;
		else
			c = Settings.topColor;
	
		for (int r = 1; r < 10; r++)	
			valueStealth[c][r-1] = valueStealth[c][r-1] * 10 / (10 - u);
	}

	// Is the AI winning? If so, its pieces are worth less
	// to encourage it to exchange ranks
	// if not, its pieces are worth slightly more
	// to encourage it to avoid exchanging ranks.
	//
	// And the more the AI is winning, the more risk
	// it must be willing to accept to win the game.
	// Otherwise, the opponent can just run some piece around
	// in circles, so that the AI cannot guess the identity
	// of the remaining pieces or the location of the flag.
	//
	// Thus the wining AI has to be encouraged to enter into
	// questionable exchanges, where it knows it will likely
	// lose material.  The hope is that it has sufficient
	// material advantage that discovery will eventually
	// lead to victory.
	//
	// But not too much more so it allows lower ranks
	// to be captured for higher ranks.  For example,
	// if a suspected opponent Two is protecting a
	// known Four, the AI should not play 3x4.   But this is
	// a close exchange, because the AI gets the value of
	// the Four (200) and the stealth value of the Two
	// in exchange for its Three (400).
	//
	// So it is important that the stealth value of the
	// opponent suspected pieces diminish as pieces are
	// removed from the board.  But we still want to encourage
	// the AI to attack unknowns so that it might be able to
	// discover opponent pieces that would allow its pieces
	// to become invincible.
	//
	// TBD: Completely unknown pieces need to be targeted,
	// such as unmoved pieces or those that appear to be
	// avoiding discovery.
	//
	// if (sumValues[Settings.topColor] == 0) and
	// its flag is bombed and the opponent has an 8
	// the ai should surrender
	//
	void valuePieces()
	{
		if (sumValues[Settings.topColor] != 0)
		for (int rank = 1; rank <= 10; rank++) {
			long v = values[Settings.topColor][rank]/2;
			v *= sumValues[Settings.bottomColor];
			v /= sumValues[Settings.topColor];
			values[Settings.topColor][rank] =
				values[Settings.topColor][rank]/2 + (int)v;
		}
	}

	// Certain higher ranked pieces may also have the prospect
	// of a positive result from attacking unknown pieces,
	// if the stealth value of the unknown low ranked
	// opponent piece is higher than the value of the player
	// piece.  For example, the opponent One is unknown, but
	// the opponent Two and Threes are known.  So the player
	// Four is "invincible" (able to attack unknown pieces
	// with good prospect), because if the opponent One
	// is the unknown defender, the player still "wins",
	// because then its other pieces become invincible.
	//
	// Note that the AI stealth values are used because the
	// opponent stealth values are lower (explained elsewhere).
	// They are reduced to 80% because the AI really doesn't want
	// to trade its Three (400 pts) just to discover the opponent One 
	// (426 pts stealth), but it certainly would trade the Three
	// to discover the opponent One and gain a minor piece (or more) in
	// the trade.  The important point is that the trade makes
	// the unknown AI Two invincible, giving it a good shot at
	// gaining back a Three.

	void genInvincibleRank()
	{
		for (int c = RED; c <= BLUE; c++) {
		int lowUnknownRank = 1;
		for (int rank = 2; rank<9;rank++) {
			if (valueStealth[Settings.topColor][lowUnknownRank-1] * 8 / 10 > values[1-c][rank])
				invincibleRank[1-c][rank-1] = true;
				
			if (unknownNotSuspectedRankAtLarge(c, rank) > 0)
				lowUnknownRank = rank;
		}
		}
	}

	// Because of shallow search depth, the AI is encouraged to
	// guard each lane with a Four, Five or Six to deter higher ranked
	// pieces from entering AI territory.
	void attackLanes()
	{
		for (int c = RED; c <= BLUE; c++) {

		// Once the opponent has few remaining expendable pieces,
		// guarding the lanes becomes unimportant, and other goals
		// take precedence, like attacking or guarding the
		// flag.

		if (hasFewExpendables(1-c, 2))
			continue;

		for (int lane = 0; lane < 3; lane++) {

			int[] attacktmp = new int[121];
			int[] fleetmp = new int[121];
			for (int j = 0; j <= 120; j++) {
				fleetmp[j] = DEST_VALUE_NIL;
				attacktmp[j] = DEST_VALUE_NIL;
			}
			for (int i : attacklanes[lane])
				for (int y = 0; y < 4; y++) {
					attacktmp[Grid.side(c, i - y*11)] = y+1;
					fleetmp[Grid.side(c, i - y*11)] = 5 - y;
				}

			Rank oppRank = Rank.SIX;
			Rank thisRank = Rank.UNKNOWN;
			Piece duplicatePiece = null;
			for (int i : attacklanes[lane])
			for (int y = 0; y < 4; y++) {
				Piece p = getPiece(i - y*11);
				if (p == null)
					continue;
				if (p.getColor() != c) {
					if (p.getRank().ordinal() < oppRank.ordinal())
						oppRank = p.getRank();
				} else {

		// Try to keep more than one non-invincible piece of the
		// same rank or multiple known pieces out of the lanes.
		// This reduces the risk of forks of known pieces.
		// It should also help to spread the defenders into
		// other lanes.
		//
		// TBD: this logic fails if there are three
		// or more pieces in the lane and the duplicates are
		// mixed in.  But mixed-in duplicates aren't as bad anyway.

					if (p.getRank() == Rank.SPY
						|| (p.getRank() == Rank.ONE && !p.isKnown()))
						genPlanB(fleetmp, c, p.getRank().ordinal(), DEST_PRIORITY_LANE);
					else if (duplicatePiece != null
						&& (duplicatePiece.getRank() == p.getRank()
						|| (duplicatePiece.isKnown()
							&& p.isKnown())))

						genPlanB(fleetmp, c, duplicatePiece.getRank().ordinal(), DEST_PRIORITY_LANE);

					else if (!isInvincible(p))
						duplicatePiece = p;

					if (p.getRank().ordinal() < thisRank.ordinal()
						&& !isStealthy(p))
						thisRank = p.getRank();
				}
			} // y

		// It is tempting to make guarding the lanes high priority, because
		// the AI often cannot see an attack on its weak flanks until too late.
		// But chasing is just as important, especially if an opponent piece
		// has made it past the lanes.  Guarding the lanes does no good in this case.
		// Worse, the AI pieces would be glued to the lanes allowing the errant
		// opponent piece to wreak havoc uncontested.

			if (oppRank.ordinal() < thisRank.ordinal())
				for (int i : attacklanes[lane]) {
					int ranksNeeded = 1;
					for (int r = oppRank.ordinal(); ranksNeeded <= 3 && r > 1; r--) {
						if (rankAtLarge(c, r) != 0) {
							int tmp2[] = genDestTmpGuarded(1-c, i, Rank.toRank(r));
							if (!isStealthy(planAPiece[c][r-1], oppRank.ordinal()))
								genNeededPlanA(0, tmp2, c, r, DEST_PRIORITY_CHASE);
							if (!isStealthy(planBPiece[c][r-1], oppRank.ordinal()))
								genNeededPlanB(tmp2, c, r, DEST_PRIORITY_CHASE);
							ranksNeeded++;
						}
					}
				}
			else {

		// AI piece is stronger or as strong as opponent piece
		// in the lane or there is no opponent piece in the lane.
		// Push the sentry piece in the lane to the most forward square in
		// the lane.  

				genPlanA(attacktmp, c, thisRank.ordinal(), DEST_PRIORITY_CHASE);
				genPlanB(attacktmp, c, thisRank.ordinal(), DEST_PRIORITY_CHASE);

		// All pieces (except eights) flee the lane
		// if neither front opponent pieces have moved,
		// because they are likely bombs.

				Piece p1 = getPiece(attacklanes[lane][0]);
				Piece p2 = getPiece(attacklanes[lane][1]);
				if (p1 != null && !p1.hasMoved()
					&& p2 != null && !p2.hasMoved())
					for (int r = 1; r <= 10; r++) {
						if (r == 8)
							continue;
						genPlanA(fleetmp, c, r, DEST_PRIORITY_LANE);
						genPlanB(fleetmp, c, r, DEST_PRIORITY_LANE);
				}
			}

		// Higher ranked pieces flee the lane

			for (int r = oppRank.ordinal()+1; r <= 5; r++) {
				genPlanA(fleetmp, c, r, DEST_PRIORITY_LANE);
				genPlanB(fleetmp, c, r, DEST_PRIORITY_LANE);
			}

		} // lane
		} // c
	}

	// Low rank piece discovery is much more important
	// than finding the structure that contains the flag.
	// The structure will become evident
	// as the opponent moves pieces and the game progresses,
	// without any need to identify the likely bombs
	// in the structure.
	//
	// The AI waits for the number of possible structures
	// to be reduced and then sends in an Eight to investigate.
	//
	// However, a strong opponent will move as few pieces
	// as possible to thwart structure discovery.  This is
	// especially true if the opponent has an unfavorable
	// position and is satisfied with a draw.
	//
	// This is often the case when the AI plays bots, because
	// most bots (and some players) aimlessly fire off 
	// expendable pieces at the start to probe the enemy's
	// position.  The AI tries to retain its expendable
	// pieces until the middle game and use them to
	// identify low ranked pieces.  So often the AI reaches
	// the middle game with expendable pieces but no clue
	// where the opponent low ranked pieces are located.
	//
	// So if the AI has a favorable mid-game position, it has
	// to use its expendable pieces to probe the opponents
	// unmoved pieces.  This is a last ditch effort to
	// avoid a draw, but could result in discovery of an
	// opponent low ranked piece or reduce the number of
	// possible structures to a point where an Eight
	// can be sent.

	// If the player is winning, opponent should
	// preserve its pieces and not randomly attack,
	// so skip this code.

	// Conversely, if the player is losing, there is value
	// in keeping pieces in structures unmoved to
	// confuse the opponent which structure holds the flag,
	// forcing the opponent to randomly attack.
	void setUnmovedValues()
	{
		for (int i=12;i<=120;i++) {
			if (!Grid.isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p == null
				|| p.hasMoved()
				|| p.isKnown())
				continue;

			int rank = p.getRank().ordinal();
			int color = p.getColor();

			if (p == planAPiece[color][rank-1])
				continue;

		// genDestFlag() tries to keep structures intact by
		// setting unmovedValue[].  Yet if the piece is a non-expendable
		// piece and it is needed, break up structure.

			if (neededRank[color][rank-1]) {
				if (!isExpendable(p)) {
					unmovedValue[i]=0;

		// Furthermove, if the piece is buried, unbury it,
		// by clearing unmovedValue from the neighbors.

					boolean buried = true;
					for (int d : dir) {
						int j = i + d;
						if (!Grid.isValid(j))
							continue;
						if (getPiece(j) == null) {
							buried = false;
							break;
						}
					}
					if (buried)
						for (int d : dir) {
							int j = i + d;
							if (!Grid.isValid(j))
								continue;
							unmovedValue[j]=0;
						}
				}
			} else

		// Moving an unmoved piece needlessly is bad play
		// because these pieces can become targets for
		// invincible opponent pieces.  The greater the piece
		// value, the greater the risk.  If the piece
		// is part of a structure that could be construed
		// as a bomb structure, it is best to leave the structure
		// intact to prevent the opponent from guessing the
		// real structure.

				unmovedValue[i] += -VALUE_MOVED -values[color][rank]/100;
		// If the opponent has a known invincible rank,
		// it will be hellbent on obliterating all moved pieces,
		// so movement of additional pieces is heavily discouraged.
		//
		// However, this can lead to draws, so eventually the AI
		// must sparingly move additional pieces, such as Eights
		// to attack flag structures or mid ranked pieces needed
		// to attack other opponent pieces.
		//
		// TBD: check if the AI has a fighting chance

			if (color == Settings.topColor
				&& rank > dangerousKnownRank
				&& rnd.nextInt(30) != 0)
				unmovedValue[i] -= VALUE_MOVED*2;

		} // i

		// Encourage movement of front line pieces
		// to clear a path so that pieces can move easily
		// from side to side.

		for (int c = RED; c <= BLUE; c++) {
			for (int x = 0; x < 10; x++)
				unmovedValue[Grid.getIndex(x, Grid.yside(c,3))] = 0;
		}
	}

	protected void chaseWithExpendable(Piece p, int i)
	{
		for ( int r = 1; r <= 10; r++)
			if (isExpendable(1-p.getColor(), r)) {
				int tmp[] = genDestTmpGuarded(p.getColor(), i, Rank.toRank(r));
				genPlanA(tmp, 1-p.getColor(), r, DEST_PRIORITY_CHASE);
				genPlanB(tmp, 1-p.getColor(), r, DEST_PRIORITY_CHASE);
			}
	}

	protected void chaseWithUnknown(Piece[][] plan, Piece p, int tmp[])
	{
		boolean found = false;
		int valuableRank = 0;
		int valuableRankValue = 0;
		for ( int r = 1; r <= 10; r++) {
			Piece a = plan[1-p.getColor()][r-1];
			if (a != null) {
				if (a.isKnown())
					continue;
				

		// If the expendable has fled from this rank before,
		// it is no longer a convincing bluffer.
		// Note that it could have fled from a higher ranked
		// piece, and still be a convincing bluffer, because
		// it may have fled to avoid detection (or capture,
		// in the case of a Spy).

				if (a.getActingRankFleeLow() == p.getRank())
					continue;
			}

			if (isExpendable(1-p.getColor(), r)) {
				if (plan == planAPiece)
					genPlanA(rnd.nextInt(2), tmp, 1-p.getColor(), r, DEST_PRIORITY_CHASE);
				else
					genPlanB(rnd.nextInt(2), tmp, 1-p.getColor(), r, DEST_PRIORITY_CHASE);
				found = true;
			} else if (valuableRank == 0 || valuableRankValue < pieceValue(1-p.getColor(), r)) {
				valuableRank = r;
				valuableRankValue = pieceValue(1-p.getColor(), r);
			}
		}
				

		// If unknown expendables are exhausted, bluff with some other minor piece

		if (!found && valuableRank != 0) {
			if (plan == planAPiece)
				genPlanA(rnd.nextInt(2), tmp, 1-p.getColor(), valuableRank, DEST_PRIORITY_CHASE);
			else
				genPlanB(rnd.nextInt(2), tmp, 1-p.getColor(), valuableRank, DEST_PRIORITY_CHASE);
		}
	}

	protected void chaseWithUnknown(Piece p, int tmp[])
	{
		chaseWithUnknown(planAPiece, p, tmp);
		chaseWithUnknown(planBPiece, p, tmp);
	}

	// Chase the piece "p"
	protected void chase(Piece p)
	{
		int i = p.getIndex();
		int chasedRank = p.getRank().ordinal();
		int[][] destTmp = new int[2][];	// encourage forward motion
		for (int guarded = GUARDED_OPEN ; guarded <= GUARDED_UNKNOWN; guarded++) {
			destTmp[guarded] = genDestTmp(guarded, p.getColor(), i);
			// this nulls out incentive for chase sequences
			destTmp[guarded][i] = DEST_VALUE_NIL;
		}

		if (p.isKnown() || p.isSuspectedRank()) {
		// no point in chasing a Nine because
		// it can easily get away
			if (p.getRank() == Rank.NINE)
				return;

		// Only the closest non-invincible known rank is assigned
		// to chase an opponent piece.
		// This prevents a pile-up of ai pieces
		// chasing one opponent piece that is protected
		// by an unknown and possibly lower piece.
		//
			int found = 0;
			int minsteps = 99;
			for (int j = chasedRank - 1; j > 0; j--)
				if (isInvincible(1-p.getColor(),j))
					continue;
				else if (knownRankAtLarge(1-p.getColor(),j) != 0
					&& Grid.steps(planAPiece[1-p.getColor()][j-1].getIndex(), i) < minsteps) {
					minsteps = Grid.steps(planAPiece[1-p.getColor()][j-1].getIndex(), i);
					found = j;
				}

		// Generate planA *and* planB.
		// If there are multiple moved pieces of the rank,
		// they will all chase the piece.

			if (found != 0) {

		// Most pieces chase at low priority.
		// The exception is a Three chasing a Four, when the opponent Two
		// is known or suspected.  This makes the Three invincible
		// IF it wins the Four, i.e. 3x4 followed by ... 1?x3 is positive,
		// because the value of the Four plus the value of the One stealth
		// is greater than the value of the Three.  (This is similar
		// to where the opponent Two and Threes are known or suspected,
		// making the player Four invincible, because 1?x4 is positive).

				int priority = DEST_PRIORITY_CHASE;
				if (found == 3
					&& unknownNotSuspectedRankAtLarge(p.getColor(), Rank.TWO) == 0)
					priority = DEST_PRIORITY_CHASE_ATTACK;
				
				int destTmp2[] = genDestTmpGuardedOpen(p.getColor(), i, Rank.toRank(found));
				genPlanA(rnd.nextInt(2), destTmp2, 1-p.getColor(), found, priority);
				genPlanB(rnd.nextInt(2), destTmp2, 1-p.getColor(), found, priority);
			}

		// Chase a known One with a Spy.
		// The chase is limited to the AI side of the board
		// until there are no opponent expendable pieces remaining
		// as a gross effort to limit the Spy's vulernability.
		//
		// Note the use of GUARDED_CAUTIOUS.  GUARDED_CAUTIOUS is used
		// for Eights and invincible pieces, because unmoved enemy ranks
		// are no detriment to the goal.  Here, GUARDED_CAUTIOUS
		// is not quite right, because unmoved ranks do pose
		// a threat to the Spy.  But this is better than
		// any of the other options.
		//
		// Once the opponent One is known and approaches
		// the AI side of the board, the Spy must be called up,
		// because the One is a dangerous piece, and could obliterate
		// the AIs moved pieces.  Because of the low probability
		// of encountering a Spy amongst many pieces, opponents
		// often play the odds of capturing just enough pieces to get
		// ahead and then play conservatively to a winning finish.

			else if (chasedRank == 1
				&& p.isKnown()
				&& hasSpy(1-p.getColor())
				&& (p.getColor() == Settings.topColor
					|| hasFewExpendables(p.getColor(), 4)
					|| p.getIndex() <= 65)) {
				int destTmp2[] = genDestTmpGuarded(p.getColor(), i, Rank.SPY);
				genNeededPlanA(rnd.nextInt(2), destTmp2, 1-p.getColor(), 10, DEST_PRIORITY_CHASE);
				chaseWithUnknown(p, destTmp[GUARDED_UNKNOWN]);
			}

		// Chase an opponent piece with an unknown piece.
		//
		// A non-active rank is occasionally summoned
		// to avoid a draw when the lack of known lower ranked
		// pieces causes both sides to stand pat.
		// This also helps to make the AI somewhat less predictable.
		//
		// The unknown chaser should not always directly chase the
		// opponent piece because this divulges its rank.  So
		// expendable pieces are sent instead, while keeping the
		// chaser near the chase.  In a situation where the
		// opponent is forced to make a decision between standing
		// pat when approached or moving next to an unknown piece,
		// the opponent almost always chooses to move.  At least
		// this is true for opponent bots.
		//
		// Yet the chaser must also directly chase at least some
		// of the time, because otherwise the opponent will
		// learn the AI bluffing strategy.
		//
		// Version 10.1 chases with both the same rank and a
		// lower rank.  This confuses the opponent about which
		// piece is the lower ranked piece.  Additional chasers
		// can cause the opponent piece to seek protection,
		// and the discovery can lead to further gain.

		//
		// Note the use of GUARDED_OPEN.  See note below.
		//
			else {
			for (int j = chasedRank; j > 0; j--)
				if (isInvincible(1-p.getColor(), j))
					continue;
				else if (rankAtLarge(1-p.getColor(),j) != 0) {
					Piece chaser = planAPiece[1-p.getColor()][j-1];
					if (chaser != null
						|| p.moves > 15
						|| rnd.nextInt(15) == 0) {
						if (chaser != null && !chaser.isKnown())
							genPlanA(rnd.nextInt(10), destTmp[GUARDED_OPEN], 1-p.getColor(), j, DEST_PRIORITY_CHASE);
						else
							genNeededPlanA(rnd.nextInt(2), destTmp[GUARDED_OPEN], 1-p.getColor(), j, DEST_PRIORITY_CHASE);
						if (j != chasedRank)
							break;
					}
				}

		// If the chase does not involve a known chaser,
		// send unknown expendable pieces to act as
		// a low ranked chasers.
		// This bluff can lead to forcing
		// the defender into making a bad decision,
		// such as attacking a bomb or the actual lower
		// ranked piece.
		// It can also box in the defender, so it has to move
		// some other pieces other than its known pieces.

		// If the chased piece is not known, it is still
		// worthwhile to send unknown expendable pieces
		// to push the chased piece into these pieces
		// so as to confirm the rank of the chased piece.
		// (There need not be a known chaser or the chaser
		// might be invincible).
		
		// Send all unknown expendable pieces, active or not.
		// Multiple pieces chasing the opponent low ranked
		// piece can create an opportunity for the real AI
		// piece to attack.
		//
		// (Recall that the AI always keeps an expendable piece
		// in motion, so call genPlan rather than genNeededPlan.
		// That rank could be known, so no chase will commence
		// until an unknown expendable piece moves, which usually
		// doesn't take very long to happen.)

				if (chasedRank <= 4 && !isInvincible(p))
					chaseWithUnknown(p, destTmp[GUARDED_UNKNOWN]);

			} // found = 0

		// Chase suspected low ranked pieces with known
		// expendables to confirm their identity

			if (p.isSuspectedRank()
				&& (chasedRank <= 3
					|| chasedRank == Rank.SPY.ordinal())) {
				chaseWithExpendable(p, i);

		// Chase with Nines
		// (GUARDED_OPEN because they can slide past guards)

				genPlanA(destTmp[GUARDED_OPEN], 1-p.getColor(), 9, DEST_PRIORITY_CHASE);
				genPlanB(destTmp[GUARDED_OPEN], 1-p.getColor(), 9, DEST_PRIORITY_CHASE);
		}

		// Chase the piece with the same rank IF both ranks are known
		// and the attacker is winning.
		//
		// Unless the ai is winning by much, it must keep its low
		// ranked pieces concealed for further advantage,
		// so that is why it will only chase a known piece
		// with another known piece of the same rank.
		//
		// But if the ai is winning by at least a rank,
		// the ai sets valueStealth to zero, so that
		// the exchange of an unknown piece for a known piece
		// furthers the game.
		//
		if ((knownRankAtLarge(1-p.getColor(), chasedRank) != 0
			|| stealthValue(1-p.getColor(), chasedRank) == 0)
			&& isWinning(1-p.getColor()) >= 0) {

			// go for an even exchange
			int destTmp2[] = genDestTmpGuardedOpen(p.getColor(), i, p.getRank());
			genNeededPlanA(rnd.nextInt(2), destTmp2, 1-p.getColor(), chasedRank, DEST_PRIORITY_CHASE);
		}

		} else if (p.hasMoved()) {
		// chase unknown fleeing pieces as well
			if (p.getActingRankFleeLow() != Rank.NIL
				&& p.getActingRankFleeLow().ordinal() <= 4) {
				for (int j = p.getActingRankFleeLow().ordinal(); j > 0; j--)
					if (!isInvincible(1-p.getColor(),j)) {
						int destTmp2[] = genDestTmpGuardedOpen(p.getColor(), i, Rank.toRank(j));
						genPlanA(rnd.nextInt(2), destTmp2, 1-p.getColor(), j, DEST_PRIORITY_CHASE);
					}

			} else if (p.getActingRankFleeLow() == Rank.UNKNOWN) {
				for ( int j : expendableRank ) {
					int destTmp2[] = genDestTmpGuardedOpen(p.getColor(), i, Rank.toRank(j));
					genPlanA(rnd.nextInt(2), destTmp2, 1-p.getColor(), j, DEST_PRIORITY_CHASE);
					genPlanB(rnd.nextInt(2), destTmp2, 1-p.getColor(), j, DEST_PRIORITY_CHASE);
				}

			} else if (p.getActingRankFleeLow() != Rank.NIL
				&& p.getActingRankFleeLow().ordinal() >= 5) {
					int destTmp2[] = genDestTmpGuardedOpen(p.getColor(), i, p.getActingRankFleeLow());
					genPlanA(rnd.nextInt(2), destTmp2, 1-p.getColor(), p.getActingRankFleeLow().ordinal(), DEST_PRIORITY_CHASE);
					int destTmp3[] = genDestTmpGuardedOpen(p.getColor(), i, Rank.toRank(p.getActingRankFleeLow().ordinal()+1));
					genPlanA(rnd.nextInt(2), destTmp3, 1-p.getColor(), p.getActingRankFleeLow().ordinal()+1, DEST_PRIORITY_CHASE);
			}

		// Also use Fours and Fives to chase unknown pieces.
		// This is done as bait to find Threes and maybe a Two.
		// And there stealth is about the same as the value of
		// an expendable, so if they are discovered, its a wash.

			for ( int j = 5; j >= 4; j--) {
				if (isInvincible(1-p.getColor(), j))
					continue;
				int destTmp2[] = genDestTmpGuardedOpen(p.getColor(), i, Rank.toRank(j));
				genNeededPlanA(rnd.nextInt(2), destTmp2, 1-p.getColor(), j, DEST_PRIORITY_CHASE);
				genPlanB(rnd.nextInt(2), destTmp2, 1-p.getColor(), j, DEST_PRIORITY_CHASE);
			}
		//
		// Chase completely unknown pieces with known AI pieces
		// and expendable unknown piece.
		//
		// If the unknown piece chases back, then
		// it will get a chase rank and the AI piece will no
		// longer chase it.  This is how the AI tries to assess
		// the rank of unknown piece without actually attacking it.
		
			for ( int j = 9; j > 0; j--)
				if (isInvincible(1-p.getColor(), j))
					continue;
				else if (knownRankAtLarge(1-p.getColor(), j) != 0) {
					int destTmp2[] = genDestTmpGuardedOpen(p.getColor(), i, Rank.toRank(j));
					genPlanA(destTmp2, 1-p.getColor(), j, DEST_PRIORITY_CHASE);
					genPlanB(destTmp2, 1-p.getColor(), j, DEST_PRIORITY_CHASE);
				}
			chaseWithExpendable(p, i);
				
		} else { // unknown and unmoved

			return;	// do not comment this out!
		}

		// Multiple lower known invincible ranks
		// and all the pieces of the rank will chase an opponent piece.
		//
		// Because lower ranks are often oversubscribed,
		// a secondary plan B is used that applies
		// to the non-active piece of the rank.
		// For example, this allows one Three to protect
		// the flag, while the other chases opponent
		// pieces, or attacks the opponent flag.
		//
		// Because the ai does not evaluate if
		// the chase will be worthwhile, it does not
		// call genNeededPlanA or B, because that would
		// cause it to move its low ranked pieces
		// which could then become subject to attack by
		// the opponent.
		//
		// A piece can be called up for the chase by
		// calling getNeededPlanA or B 
		// only if the piece is a known invincible piece,
		// the piece is an invincible win piece,
		// or there is only one lower opponent piece of lower
		// known rank remaining and the value of the chased piece
		// is worthwhile to lose stealth to capture.
		//
		// This last condition requires an example.
		// The AI has an unknown invincible Two and the
		// opponent One is known.   The opponent has a known Three
		// or a known Four. This code will move the Two to chase
		// the Three or Four. It will not move the Two to chase
		// a lesser valued piece.
		//
		// It is best not to chase a Two or Three with an unknown One
		// even if the Spy is off the board because if the One
		// is discovered (or guessed because it chased the Two),
		// then the Two becomes invincible.
		// So defensively, keep the One unknown as long as possible.
		// Once it becomes known, then it can chase the Two.
		//
		// On the other hand, an opponent Two or Three
		// can still cause havoc
		// even if the AI One is unknown.  The Two can chase known
		// pieces and block lanes.  So the Two needs to be chased
		// by expendable pieces posing as the One and the One needs
		// to approach the Two, but not chase it, aiming to
		// use the expendable pieces posing as Ones to force
		// the Two to flee towards it.  In addition, the One must
		// always be shielded by other pieces to prevent its
		// discovery.  In the AI implementation, the chase of
		// a known Two by an unknown One
		// is limited to its side of the board.
		//
		// Note that this defensive strategy limits discovery of
		// the opponent Spy.  It is unlikely that the Spy will move
		// to the opposite side of the board if the opponent One
		// is still unknown.  Because the chase is limited to
		// the player's side of the board, indirect discovery of
		// the Spy would not occur.  Thus, the AI does need to
		// send its expendable pieces to chase the Two (and
		// occasionally the One to maintain the bluff) to the
		// opposite side of the board.
		//
		// Ideally, the ai would predict if a chase
		// would be successful, and the only way I know
		// of doing that is to use the search tree.
		// So this code is the best that the ai can do.
		//

		for (int j = 1; j <= chasedRank; j++) {

			if (!isInvincible(1-p.getColor(), j))
				continue;

			if (rankAtLarge(1-p.getColor(),j) == 0)
				continue;

		// If the opponent has an invincible piece,
		// the AI must chase it to attempt an even exchange.
		// While this just leads to another opponent rank becoming
		// invincible, it is the only way to end the game,
		// because otherwise the invincible piece just runs amok.
		// Eventually, if the AI is indeed winning, the
		// opponent will run out of invincible pieces.
		// (See EVEN).

		// Only go for an even exchange of invincible pieces
		// if the player is winning.

			if (j == chasedRank
				&& (isWinning(1-p.getColor()) < 0
					|| knownRankAtLarge(1-p.getColor(),j) == 0
					|| chasedRank == 1 && hasSpy(1-p.getColor())))
				continue;

			int destTmpA[] = genDestTmpGuarded(p.getColor(), i, Rank.toRank(j));
			int destTmpB[] = genDestTmpGuardedOpen(p.getColor(), i, Rank.toRank(j));

			if (knownRankAtLarge(1-p.getColor(),j) != 0
				|| stealthValue(1-p.getColor(), j) <= values[p.getColor()][unknownRank[p.getColor()]]) {
		// Prior to version 9.6 it was thought that
		// invincible pieces cannot always chase at high priority,
		// otherwise the AI will use these pieces in an
		// ad infinitum chase.  So half of the time it chased
		// at lower priority.
		//
		// The sole exception was when the *chased* rank is invincible,
		// because until the opponent invincible rank is captured or
		// cornered, it will just likely
		// chase the players pieces around the board ad inifinitim
		// until they are captured.  So the AI must continually chase
		// the invincible rank with invincible pieces until it is
		// cornered in order for the game to continue.
		//
		// If a lone chased piece is always chased at high priority,
		// fleeing creates a pointless chase.
		// However, if the chased piece is nearby another possible
		// target, then perhaps both pieces might not be
		// able to flee at once, so it is suitable to chase
		// the clump at high priority.  And it is
		// important to try to chase a clump at high priority because
		// if the AI plays some other moves, it gives the opponent
		// time to react, such as bringing in a defensive
		// invincible piece.
		//
		// This reliance on the oracle is simply a guess and may or
		// may not lead to material gain, because the targets
		// could flee before the chaser arrives or a superior
		// protector could come into play.  The important
		// condition is to stop chasing at high priority
		// once the clump dissolves in order to avoid continuous
		// chase sequences.
		//
		// The goal is to corner the chased piece
		// rather than approach it and push it
		// around randomly.  This is especially true if the
		// chased piece is invincible.  The idea is to bring another
		// chase piece towards the chased piece and get the search
		// tree to discover the proper moves to capture it.
		// This also avoids mindless chases around the board.
		// (Mindless chases do often result in material gain,
		// but the goal of this programmer is to avoid them).
		//
		// The One chases at three different priorities.  If the
		// opponent Spy is gone, it can chase at the highest priority,
		// just like other invincible pieces.  But if the opponent
		// Spy is still lurking, it chases opponent invincible
		// pieces as a lower priority as a defense measure.  Other
		// opponent pieces are chased at low priority, because
		// the One probably will not be able to capture the piece
		// if it is protected by an unknown.

				int priority = DEST_PRIORITY_CHASE;
				if (isInvincible(p))
					priority = DEST_PRIORITY_CHASE_DEFEND;
				else if (!(j == 1 && hasUnsuspectedSpy(p.getColor()))
					&& grid.movablePieceCount(p.getColor(), i, 1) >= 2)
					priority = DEST_PRIORITY_CHASE_ATTACK;

				genPlanA(1, destTmpA, 1-p.getColor(), j, priority);
				genPlanB(1, destTmpB, 1-p.getColor(), j, priority);

		// The AI is cautious in sending its unknown low ranked pieces
		// to chase an opponent pieces because of the risk of
		// discovery of its piece and loss of stealth.
		//
		// The compromise is to send only pieces that have already moved
		// until the opponent has few expendables that might try to
		// attack the low ranked piece.

		// But the expendable pieces can always chase,
		// and the idea is to use the expendable pieces
		// to push the opponent piece into an area where it can
		// be attacked by the low ranked piece. 

		// The unknown low ranked piece must also be careful
		// to rarely approach the chased piece,
		// or opponent will guess its rank.  So the piece
		// must mostly keep one square away to leave the opponent
		// guessing.

			} else if (j <= invincibleWinRank[1-p.getColor()]
				|| (lowerRankCount[p.getColor()][j-1] < 2
					&& stealthValue(1-p.getColor(), j) < values[p.getColor()][chasedRank])) {
				int priority = DEST_PRIORITY_CHASE;
				if (grid.hasAttack(p))
					priority = DEST_PRIORITY_CHASE_DEFEND;
				if (hasFewExpendables(p.getColor(), 4)
					|| p.getIndex() <= 65) {
					genNeededPlanA(rnd.nextInt(10), destTmpA, 1-p.getColor(), j, priority);

		// The stealth of the unknown AI One (like the Spy)
		// is so important
		// that it cannot be risked by sending it to the opposite
		// side of the board until all the opponents expendable
		// pieces have become known.  Until then, it is
		// limited to a defensive position.
		// Either the One stays one square away
		// on its side of the board, or it flees (see Flee()).

				} else if (j != 1)
					genPlanA(rnd.nextInt(2), destTmpA, 1-p.getColor(), j, priority);
				// tbd: PlanB as well
				chaseWithUnknown(p, destTmp[GUARDED_UNKNOWN]);

			}
		} // for

	}

	// > 0 : the rank is still at large, location unknown
	// = 0 : the rank is gone or if large, the location is guessed
	// < 0 : the rank is still at large, multiple locations are guessed
	protected int unknownNotSuspectedRankAtLarge(int color, int r)
	{
		return unknownRankAtLarge(color, r) - suspectedRankAtLarge(color, r);
	}

	protected int unknownNotSuspectedRankAtLarge(int color, Rank rank)
	{
		return unknownNotSuspectedRankAtLarge(color, rank.ordinal());
	}

	protected int suspectedRankAtLarge(int color, int r)
	{
		return suspectedRank[color][r-1];
	}

	protected int suspectedRankAtLarge(int color, Rank rank)
	{
		return suspectedRankAtLarge(color, rank.ordinal());
	}

	// The number of unknown expendable pieces still at large
	// greatly determines the relative safety of a valuable rank
	// from discovery.  Note that expendables include Eights.
	// Because some expendable ranks may remained buried
	// in bomb structures or simply are not being moved,
	// uUnknownExpendableRankAtLarge is rarely zero.
	// But this should not deter valuable ranks from attack.
	// So the AI subtracts a percentage (1/3) of
	// possibleUnknownMovablePieces in determining safety.
	private boolean hasFewExpendables(int color, int n)
	{
		return nUnknownExpendableRankAtLarge[color] - (possibleUnknownMovablePieces[color] / 3) <= n;
	}

	// Target ai or opponent flag
	//
	// Static position analysis is a poor substitute
	// for the search tree in determining whether the flag
	// is susceptible to attack and creating countermeasures.
	// But because the search tree
	// is shallow, the AI may not realize the opponent can
	// capture the flag before it is too late.
	//
	// One often used strategy is to simply load the flag area
	// with guards of various ranks.  While effective, this
	// defensive strategy often leads to draws or even losses
	// if the opponent is persistent.  It also makes it obvious
	// where the flag is located.
	//
	// Instead, the AI does open path analysis and always keeps
	// a defender of appropriate rank one step closer to the
	// square adjacent to the flag on the opponent approach path.
	//
	// (Note: it may well be possible to use a limited search tree
	// to accomplish a better result, albeit with much more
	// computation.)

	private void defendFlag(Piece flagp)
	{
		int color = flagp.getColor();
		int flagi = flagp.getIndex();

		// TBD:
		// How to bluff the opponent into thinking some other
		// piece is the flag while still guarding the flag?
		if (color == Settings.topColor
			&& flagp != flag[color])
			return;

		// Even when the flag is not completely bombed,
		// the approach could be guarded by an invincible rank
		// thwarting access.  So Eights are still needed
		// to clear flag structure bombs.
		for (int d : dir) {
			int bi = flagi + d;
			Piece bp = getPiece(bi);
			if (bp != null && bp.getRank() == Rank.BOMB) {
				int[] destTmp = genDestTmpGuardedOpen(color, bi, Rank.EIGHT);
				genNeededPlanA(0, destTmp, 1-color, 8,  DEST_PRIORITY_ATTACK_FLAG);
			}
		}

		// Note: If a strong piece has not moved, the ai does
		// not move it to protect the flag.  This is a tradeoff,
		// because while the strongest piece is desired to
		// protect the flag, moving it makes it subject to attack.
		// So only the strongest moved piece protects the flag
		// (checking planAPiece) and genNeededPlanA is not called.
		// This also is a subterfuge measure, because if the ai
		// always called in its strongest piece to protect the flag,
		// the opponent could easily guess the location.
		//

		// Note that getDestTmp is called with "color" rather
		// than "1-color" and the plans are also called
		// with "color".  This is correct but not intuitive.
		// This means that the flag defender sees the call
		// to protect its flag only if there is an unguarded
		// path.  (Non-bombed flags are set to known.)
		// As long there is no unguarded path to the flag,
		// the attacker may not risk an attack, so
		// the defender is not called up.  Once there is an unguarded
		// path to the defender of lower rank than the closest
		// opponent, the defender is drawn towards the flag.

		for (int d: dir) {
			int side = flagi + d;
			if (!Grid.isValid(side))
				continue;
			Piece pside = getPiece(side);

			int destTmp[] = genDestTmp(GUARDED_OPEN, color, side);
			int stepsAttacker = 99;
			Piece pAttacker = null;
			for (int i = 12; i < 120; i++) {
				Piece p = getPiece(i);
				if (p == null || p.getColor() != 1 - color)
					continue;
				if (destTmp[i] < stepsAttacker) {
					stepsAttacker = destTmp[i];
					pAttacker = p;
				}
			}
			if (pAttacker == null)
				continue; // no open path

		// If the flag is partially bombed, the bomb
		// must be protected from an unknown or Eight

			Rank rankAttacker = pAttacker.getRank();
			int attackerValue = pieceValue(pAttacker);

			if (pside != null
				&& pside.getRank() == Rank.BOMB) {
				if (!(rankAttacker == Rank.UNKNOWN
					|| rankAttacker == Rank.EIGHT))
					continue;

		// If the approaching piece is unknown, assume that it
		// could be a low ranked piece on a kamikaze attack to discover the flag,
		// so the AI needs an equal or even lower piece.

			} else if (rankAttacker == Rank.UNKNOWN) {
				rankAttacker = Rank.toRank(lowestUnknownNotSuspectedRank);
				attackerValue = pieceValue(1-color, rankAttacker);
			}

			Piece defender = getDefender(color, destTmp, rankAttacker, attackerValue, stepsAttacker);
			if (defender == null)
				continue;	// no imminent danger

		// Even if the unbombed flag is not known, call up
		// the closest defender, even if it means moving
		// another unmoved piece.
		// The opponent (especially bots) may be
		// throwing pieces at any unmoved piece hoping that
		// it may be the flag and the active piece too far
		// away.
		// TBD: If the active piece is able to defend, then
		// use it.  Fix this in getDefender().

			setDefender(defender, destTmp, DEST_PRIORITY_DEFEND_FLAG);
		} // dir
	}

	// Determine if piece at "index" is at risk of attack
	// and if so, what rank should be moved to protect it?

	// Of course, this algorithm isn't perfect anyway.  If
	// the attacker has an open path, but the player does not,
	// the AI fails to defend.  This code cannot take the place
	// of the search tree which discovers how to move the players
	// pieces optimally.  This code is a pale effort to improve defensive
	// measures past the search horizon.

	// If stepsTarget != 0, find the closest piece capable
	// of defending.

	// If stepsTarget == 0, just find the highest ranked piece capable
	// of defending.

	private Piece getDefender(int color, int destTmp[], Rank attacker, int attackerValue, int stepsTarget)
	{
		int attackerRank = attacker.ordinal();

		int stepsDefender = 99;
		Piece pDefender = null;
		int stepsAttacker = 99;

		for (int i = 12; i < 120; i++) {
			if (!Grid.isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p == null)
				continue;

			if (p.getColor() == 1 - color) {
				if (destTmp[i] < stepsAttacker) {
					stepsAttacker = destTmp[i];
				}
				continue;
			}

		// The Spy is the better piece to protect the flag area
		// against a One.  (A One can be used, but after 1x1,
		// the flag area is unprotected).

			if (attacker == Rank.ONE
				&& p.getRank() == Rank.SPY) {
				stepsDefender = destTmp[i];
				pDefender = p;
				continue;
			}

			if (p.getRank().ordinal() > attackerRank
				|| (!p.isKnown()
					&& stealthValue(p) > attackerValue))
				continue;

			if (stepsTarget != 0 && destTmp[i] < stepsDefender
				|| (stepsTarget == 0
					&& destTmp[i] != DEST_VALUE_NIL
					&& (pDefender == null
					|| p.getRank().ordinal() > pDefender.getRank().ordinal()))) {
				stepsDefender = destTmp[i];
				pDefender = p;
			}
		}

		// Prior to version 9.4, null was returned only when:
		// stepsDefender < stepsTarget // no imminent danger
		// So even if the defender had no chance of beating
		// the attacker (stepsDefender > stepsTarget), it
		// was rewarded if it approached its flag.
		// It was argued that because the attacker does not
		// really know where the flag is, this might get the
		// defender close enough to defend before the attacker
		// decided to attack.
		//
		// But this backfires.  Consider the example:
		// B2 R6 -- -- -- -- -- -- |
		// -- -- -- -- BB BF BB B8 |
		//--------------------------
		// The AI is Red and has the move.  But for every move
		// it makes to approach the flag, Blue Two follows.
		// Every step that Blue takes towards its flag is rewarded.
		// But if Red moved away from Blue Flag, Blue would not
		// be rewarded because its flag is no longer in danger.
		// 
		// When the AI was protecting its own flag, it would
		// see a small reward of getting ahead of the attacker,
		// but a larger extended reward if it allowed the attacker
		// to go past and then chase it.
		//
		// This is exactly opposite the desired result.
		// So in Version 9.4, the reward was increased and
		// limited to only when absolutely necessary:
		// (stepsTarget != 0 && stepsDefender != stepsTarget - 1)) // no imminent danger
		//
		// Yet this introduced another bug because the
		// defender does not realize that it is the piece
		// defending the flag and could wander off.
		// So in version 9.5, the pre-9.4 comparison was
		// restored, but cropped the reward to only
		// the adjacent squares.
		//
		// TBD: when the flag is no danger, and then the
		// defender wanders away, it can cause loss of the
		// game, or back-and-forth moves.  For example,
		// -- -- RF
		// -- -- --
		// -- -- --
		// -- -- --
		// -- -- xx
		// -- R7 xx
		// B? -- --
		// The flag is in no danger, so R7 moves down and
		// unknown Blue moves up.  Now the flag is in danger,
		// so R7 moves up, and unknown Blue moves down.

		if (pDefender == null  	// attacker not stopable
			|| (stepsTarget != 0 && stepsDefender < stepsTarget)
			|| (stepsTarget == 0 && stepsDefender < stepsAttacker)) // no imminent danger
			return null;

		return pDefender;
	}

	void setDefender(Piece defender, int tmp[], int priority)
	{
		int r = defender.getRank().ordinal();
		int color = defender.getColor();
		planAPiece[color][r-1] = defender;
		genNeededPlanA(0, tmp, color, r, priority);
	}

	// Because the search tree is so shallow that it is often
	// too late to respond to an approaching attacker to the
	// flag bomb structure, defenders must always try to
	// position themselves between the bombs and the attackers.
	// Often an attack on the bomb structure involves a low ranked
	// piece and an unknown or an eight.  The low ranked piece
	// escorts the eight to the bomb structure.  So the defender
	// must push both the low ranked piece and the eight away.
	//
	// If the defender just focused on the closest attacker, it
	// would be possible for a low ranked attacker to draw away
	// the defender and allow the structure to be attacked.
	// For example,
	//
	// -- RB RF RB -- --
	// -- -- RB -- -- --
	// -- -- R1 -- -- --
	// -- -- -- -- -- --
	// -- -- xx xx B8 --
	// -- B1 xx xx -- --
	// Blue One and Blue Eight are both 5 steps away, too far
	// for the search tree depth.  Red One moves to the left
	// to position itself between Blue One and the bomb structure.
	// This allows Blue Eight to approach and attack the right bomb.
	private void flagBombTarget(Piece flagp)
	{
		int color = flagp.getColor();
		int flagi = flagp.getIndex();
		assert color == Settings.topColor : "flagBombTarget only for AI";

		// Determine if any bomb is subject to attack
		// and take defensive measures.
		// TBD: if there are two unknown attackers,
		// but if one has a suspected rank, focus on the other one.

		for (int d : dir) {

			int bi = flagi + d;
			if (!Grid.isValid(bi))
				continue;
			Piece bp = getPiece(bi);
			if (bp == null || bp.getRank() != Rank.BOMB)
				continue;

			//
			// check all possible approaches to bomb
			//

			for (int dbomb : dir ) {
				int bd = bi + dbomb;
				if (!Grid.isValid(bd))
					continue;
				if (getPiece(bd) != null)
					continue;

				int stepsAttacker = 99;
				int stepsProtector = 99;
				Piece pAttacker = null;
				Piece pProtector = null;
				int destTmp[] = genDestTmp(GUARDED_OPEN, color, bd);
				for (int i = 12; i < 120; i++) {
					Piece p = getPiece(i);

		// If the closest enemy piece has not moved,
		// it probably is a bomb.

					if (p == null
						|| p.getColor() != 1 - color
						|| !p.hasMoved())
						continue;
					if (p.getRank().ordinal() <= 8) {
						if (destTmp[i] < stepsProtector) {
							stepsProtector = destTmp[i];
							pProtector = p;
						}
						continue;
					} else if ((p.getRank() == Rank.UNKNOWN
						|| p.getRank() == Rank.EIGHT)
						&& destTmp[i] < stepsAttacker) {
						stepsAttacker = destTmp[i];
						pAttacker = p;
					}
				}
				if (pAttacker == null)
					continue; // no open path

		// Thwart the approach of the closest unknown or eight piece.
		// (Note: DEFEND_FLAG must be high enough so that if
		// the defender is an unmoved piece part of a possible
		// bomb structure, it will still be the best pruned move).

		// TBD: Note that if the attacker is unknown
		// the AI relies on a Eight or less to defend.
		// Obviously, that would be no deterrent
		// to a lower-ranked unknown attacker.

				Piece defender = getDefender(color, destTmp, Rank.EIGHT, pieceValue(1-color, Rank.EIGHT), stepsAttacker);
				if (defender != null) {
					setDefender(defender, destTmp, DEST_PRIORITY_DEFEND_FLAG);
				} else {
					// TBD: try looking for an Eight
				}

		// If the flag is known, try to keep the protector, if any,
		// away from the flag, because otherwise the protector
		// may attack the defender, giving the attacker a clear
		// shot at the flag.  (If the flag is not known, it is
		// best not to react to a known opponent piece in the
		// area, because overreaction is a dead giveaway).

				if (pProtector != null && flagp.isKnown()) {
					defender = getDefender(color, destTmp, pProtector.getRank(), pieceValue(pProtector), stepsProtector);
					if (defender != null) {
						int[] destTmp4 = genDestTmpGuarded(1-color, pProtector.getIndex(), pProtector.getRank());
						setDefender(defender, destTmp4, DEST_PRIORITY_DEFEND_FLAG_AREA);
					}
				}


			} // dbomb
		} // d
	}

	// depends on possibleFlag() which can set the ai flag
	// isSuspectedRank to true
	// if it detects an obvious bomb structure

	// AI FLAG:
	// If the ai assumes opponent has guessed its flag
	// and sets the flag to be known,
	// then the ai will leave pieces hanging if the opponent
	// can take the flag, because the ai assumes the opponent
	// is going to take the flag rather than the ai pieces.
	// If the opponent does not exactly know, it
	// may be better to allow the opponent to take the
	// flag in the search tree, moving the ai pieces
	// as if nothing is amiss (bluffing).
	// If the opponent HAS guessed it correctly, then
	// the flag needs to be set known so the ai pieces
	// respond.

	// But the ai also assumes
	// that unknown pieces take bombs and transform
	// into eights.  So this makes the ai vigilant in
	// protecting the bombed area from unknown pieces.

	private void aiFlagSafety()
	{
		Piece pflag = flag[Settings.topColor];
		int flagi = pflag.getIndex();
		int color = pflag.getColor();

		assert pflag.getRank() == Rank.FLAG : "aiFlag not ai flag?";
		assert color == Settings.topColor : "flag routines only for ai flag";
		// initially all bombs are worthless (0)
		// value remaining bombs around ai flag

		boolean bombed = true;
		for (int d : dir) {
			int j = flagi + d;
				
			if (!Grid.isValid(j)
				|| Grid.getY(j) > 3)
				continue;
			Piece p = getPiece(j);
			if (p == null) {
				bombed = false;

		// If flag area had a bomb removed, the flag is known

				// if (getSetupRank(j) == Rank.BOMB)
				// 	makeFlagKnown(pflag);
				continue;
			}

		// If the opponent is adjacent to flag
		// or flag has a known bomb
		// then the ai guesses that the flag is known

			// if (p.getColor() != color 
			// 	|| (p.getRank() == Rank.BOMB && p.isKnown())) {
			// 	makeFlagKnown(pflag);
			// }

			if (p.getRank() == Rank.BOMB)
				p.setAiValue(aiBombValue(p.getColor()));
			else
				bombed = false;
		}

		// Target the flag if not bombed,
		// otherwise target the bombs (if there still are eights)

		if (!bombed) {

		// opponent color eights are now expendable

			setExpendableEights(Settings.bottomColor);

		// Setting the flag to always known is questionable
		// because its location is almost never
		// known for sure by the opponent.  If the flag is set
		// to known, the AI will make every effort to prevent
		// the flag from attack.  Therein lies the problem.
		// If the flag is known and can be attacked successfully,
		// the AI is prone to leaving its pieces hanging, because it
		// assumes that the opponents best move is to take the
		// flag rather than its pieces.  This is a horizon effect,
		// and ideally qs() should return a worse result
		// and the AI should attempt to minimize material loss
		// even if the flag can be taken.  However, if the flag
		// attacker reaches the flag at ply N and the AI does not
		// evaluate beyond ply N, the AI will leave its pieces
		// hanging because for the opponent to take an AI piece,
		// it will delay reaching the flag.
		//
		// I do not see a way around this problem.
		// The issue is correctly
		// ascertaining whether the flag is indeed known.  Even
		// if it is not known, the attacker could clumsily attack
		// it and win, so the flag always needs to be known
		// at some point.  Perhaps the ai should assume the flag
		// unknown until an attacker is within some distance?
		// This will hamper its effort to protect the flag if the
		// flag is indeed known but if the distance is less than
		// the maximum search ply, it prevents the horizon effect.
			// makeFlagKnown(pflag);

			defendFlag(flag[Settings.topColor]);
		}

		// Always protect any remaining bombs in the
		// the flag bomb structure, even if the flag
		// is not known (i.e multiple structures remaining)
		// because the opponent can make a lucky guess,
		// especially if the opponent has expendable eights
		// that it can sacrifice by throwing them at multiple
		// structures (just like the AI does).

		if (rankAtLarge(1-color, Rank.EIGHT) != 0) {

			flagBombTarget(pflag);

			if (pflag.isKnown() && Grid.getY(flagi) == 0) {

		// Keep an Eight guard nearby just in case
		// (This blows the cover for the flag
		// structure, so do this only if known).
		//
		// TBD: The guard is attracted to a fixed square
		// above the flag.
		// It would be better to check the direction of
		// the attacker and move between.  But if this
		// is coded, make sure that moving the defender
		// does not cause back and forth motion.
		// (The top square should be close enough that
		// minimax should ward off any attackers.)

				int destTmp[] = genDestTmp(GUARDED_OPEN, color, flagi + 22);
				Piece defender = getDefender(color, destTmp, Rank.SEVEN, pieceValue(1-color, Rank.SEVEN), 0);

		// TBD: The guard is only necessary if there isn't already
		// some other piece blocking the path.  Otherwise, in an equal
		// endgame, the AI will defend its flag rather than attack.

				if (defender != null
					&& !isInvincible(defender))
					setDefender(defender, destTmp, DEST_PRIORITY_DEFEND_FLAG_BOMBS);
			}
		}
	}

	void genDestFlag()
	{
		for (int c = RED; c <= BLUE; c++) {

		Piece pflag = flag[c];
		if (pflag == null)
			continue;

                // revalue eights
                // (Note: maybe_count == 0 calls setExpendableEights even
                // if there are not any left.  This is necessary because
                // Flag value > aiBombValue > Eight value.)

                int opponentEightsAtLarge = rankAtLarge(1-c, Rank.EIGHT);
		int bombedStructuresRemaining = maybe_count[c] - open_count[c];

                if (bombedStructuresRemaining == 0
			|| bombedStructuresRemaining + 1 < opponentEightsAtLarge)

		// at least 1 opponent color eight is expendable
		// (The AI keeps one more Eight around than
		// necessary to insure for an unforeseen mortality).

                        setExpendableEights(1-c);

                else if (opponentEightsAtLarge <= Math.min(bombedStructuresRemaining, 2) + 1) {

		// Opponent Eights become more valuable as their number
		// falls below the number of player possible structures.
		// This value increase is reduced by the number of
		// remaining player pieces that can defend the flag.
		// This is done to deter an exchange of Eights when
		// each player has the same number of Eights and structures,
		// so that the player who has more lower ranks, should
		// use its lower ranks to keep the opponent Eight at bay
		// while using its Eight to attack.

                        values[1-c][Rank.EIGHT.ordinal()]
                                += (30 - lowerRankCount[c][8])*2;
		}

		// Note:Flag value depends on opponent Eight value above

		setFlagValue(pflag);

		// Making the flag known eliminates the flag
		// from the piece move lists, which means that
		// it offers no protective value to pieces that
		// bluff against lower ranked pieces.

		// Setting the opponent flag known is a gamble.
		// If the flag is known, a low ranked AI piece in the area
		// will attack the flag, which could lose the game
		// For example,
		// R8 R1
		// BB B7
		// BB BB -- BF
		// R8xBB, B7xR8, R1 attacks the corner bomb which it
		// thinks is the flag, but the flag is actually elsewhere.

		// More often, this arises when the flag is not bombed,
		// so it could be one of many pieces.  If the flag
		// is set to known, then the AI will hammer all of its
		// pieces into the remaining unknowns.

		if (maybe_count[c] == 1)
			makeFlagKnown(pflag);

		if (opponentEightsAtLarge == 0)
			continue;

		// The ai only uses its eights to attack flag structures
                // if it has enough remaining.  However, it still approaches
                // flag structures with unknown expendable pieces.
                // This subterfuge often can cause the opponent into
                // making a mistake, attacking an unknown piece that
                // approaches the flag, perhaps resulting in material
                // gain for the AI.

                // this code is only for opponent bombs

		// Eights begin to attack bomb structures as the
		// number of possible structures diminish
		boolean eightAttack = (maybe_count[c] <= 3
			|| maybe_count[c] - open_count[c] <= opponentEightsAtLarge);

                if (c == Settings.bottomColor
                        && isBombedFlag[c]
                        && eightAttack)
				for (int d : dir) {
					int j = flag[c].getIndex() + d;
					if (!Grid.isValid(j))
						continue;

					destFlagBomb(j,
						maybe_count[c] - open_count[c] <= opponentEightsAtLarge);
				}

		defendFlag(pflag);
		genDestFlag(pflag.getIndex());

		// Unmoved pieces that remain in structures that could
		// contain the flag should remain unmoved if possible,
		// to confuse the opponent about the flag location,
		// unless the player is winning by a large edge.

		// unmovedValue[] distorts unknown exchanges, so this
		// needs to be as low as necessary to keep the
		// pieces unmoved.  This distortion shows up in
		// close exchanges.  For example,
		// | R4 --
		// | B5 B?
		// | B? B? B?
		// -------
		// R4xB5 and B?xR4 is 50 - 100 + 40 (Two stealth) = -10.
		// But if B? is on the back rank, the unmovedValue is 6,
		// plus VALUE_MOVED (5), which leads Red to attack.

		for (int x = 0; x < 10; x++) {
			int i = Grid.getIndex(x, Grid.yside(c, 0));
			Piece p = getPiece(i);
			if (p == null || p.getRank().ordinal() <= 3)
				continue;
			boolean altFlag = true;
			for (int d : dir) {
				int j = i + d;
				if (!Grid.isValid(j))
					continue;
				p = getPiece(j);
				if (p == null
					|| (p.isKnown() && p.getRank() != Rank.BOMB)
					|| p.hasMoved()
					|| p.getRank().ordinal() <= 3) {
					altFlag = false;
					break;
				}
			}
			if (altFlag
				&& isWinning(c) < VALUE_THREE
				&& (isWinning(c) < 0 || rnd.nextInt(20) != 0))
				for (int d : dir) {
					int bi = i + d;
					if (!Grid.isValid(bi))
						continue;
					p = getPiece(bi);
					unmovedValue[bi] -= 4;
				}
		} // x
		} // c
	}

	// Establish location "i" as a flag destination.
	void genDestFlag(int i)
	{
		Piece flagp = getPiece(i);
		int color = flagp.getColor();

		// Send in three lowly ranks (including an Eight, if any)
		// to attack the suspected flag.

		// Note: if the flag is still bombed, the
		// destination matrix will not extend past the bombs.
		// So the lowly rank and Eight will only be activated IF
		// there is an open path to the flag.

		int destTmp[] = genDestTmp(GUARDED_OPEN, flagp.getColor(), i);
		int attackers = 0;
		for (int k = 10; k > 0 && attackers < 3; k--) {
			if (k == 10 && rankAtLarge(flagp.getColor(), Rank.ONE) != 0)
				continue;
			if (rankAtLarge(1-flagp.getColor(),k) != 0) {
				genNeededPlanA(0, destTmp, 1-flagp.getColor(), k, DEST_PRIORITY_ATTACK_FLAG);
				attackers++;
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
	private void destFlagBomb(int j, boolean sendEight)
	{
		Piece p = getPiece(j);
		assert !(p == null
			|| (p.isKnown() && p.getRank() != Rank.BOMB)
			|| p.hasMoved()) : "destFlagBomb() called on non-bomb";

		// If the structure is intact, encourage the Eight
		// to sacrifice itself to open the flag to attack
		// by other pieces.  Otherwise, the bomb is worthless
		// to the Eight unless it can get at the flag.  By
		// continuing to remove more bombs around the structure,
		// it opens it up to combined attack by other pieces.
		if (sendEight)
			p.setAiValue(aiBombValue(p.getColor()));
		
		// stay near but don't get in the way
		int near;
		if (p.getColor() == Settings.bottomColor) {
			near =  j - 10;
			if (!Grid.isValid(near))
				near = j - 12;
			assert Grid.isValid(near) : "near is not valid?";
		} else
			near = j;

		// (Before version 9.5, an active piece was sent to protect
		// the Eight.  But this is redundant because opponent
		// pieces are already chased in chase(), so if an opponent
		// piece is guarding the flag, the AI will target it anyway.
		// Moreover, if the flag is guarded by a low ranked piece
		// (usually the case), sending a known piece of
		// higher rank is pointless and counterproductive.
		// 

		int destTmp[] = genDestTmp(GUARDED_OPEN, p.getColor(), near);

		if (sendEight) {

		// Send the miner(s)
		//
		// The active miner heads directly for the flag, regardless of any protection,
		// so GUARDED_OPEN is used.  Another miner, if available, pursues the flag
		// through an unguarded path.  (At least one miner has to head directly for
		// the flag regardless of protection; otherwise, the miner would simply retrace
		// its steps to an alternative path, the protection would move, and the miner
		// would retrace again).

			if (hasFewExpendables(p.getColor(), 4))
				genNeededPlanA(0, destTmp, 1-p.getColor(), 8, DEST_PRIORITY_ATTACK_FLAG);
			int destTmp2[] = genDestTmpGuardedOpen(p.getColor(), j, Rank.EIGHT);
			genPlanB(destTmp2, 1-p.getColor(), 8, DEST_PRIORITY_ATTACK_FLAG);
		}

		// Send along unknown expendable pieces as a subterfuge measure.
		// This is done even if sendEight is false because
		// it is useful to probe the opponents defense.
		// If the protectors of the bomb structure are outnumbered,
		// they often can be drawn towards the extra unknowns
		// allowing the eight to attack the bomb.
		//
		// Note that GUARDED_OPEN is used to encourage the unknowns
		// to approach the flag area, even if it is guarded.
		// This is because the opponent may have to assume that
		// an approaching unknown is an Eight, which can
		// (1) cause the guard to open up another side of
		//	the flag area.
		// (2) create a discovery opportunity if the guard is
		//	unknown
		// (3) keep the guard pinned near the flag area, allowing the
		//	player to attack other pieces

		chaseWithUnknown(p, destTmp);
	}

	// Generate a matrix of consecutive values with the highest
	// value at the destination "to". (Lee's algorithm).
	//
	// For attackers, "color" is the color of the defender
	// (usually at destination "to" or nearby).
	// A piece of any color blocks the destination from discovery,
	// unless the caller is invincible or the Spy, in which case
	// the maze continues through moved pieces.
	//
	// If the destination "to" contains a piece of "color",
	// the maze continues.  If the destination "to" contains
	// a piece of the opposite "color", this function returns immediately.
	//
	// Seed the matrix with "n" at "to".
	//
	// If guarded is true, then the matrix will avoid passing
	// any moved piece of unknown or superior rank.
	// This is used when sending eights.
	//
	// This matrix is used to lead pieces to desired
	// destinations.
	private int[] genDestTmpCommon(int guarded, int color, int to, Rank attackRank)
	{

	// TBD: The destination matrices were originally designed for
	// ranks, not pieces.  The idea was that all ranks were equivalent
	// in moving around the board.  But this idea is not quite correct.
	// Unknown ranks are safer to move around than known ranks.
	// Also, winFight(Piece, Piece) requires piece inputs to determine
	// the winner of a fight, because unknown pieces may have
	// an associated flee rank that can be meaningful in determining
	// the winner of the fight.
	// 
	// So the hack is to use rank to index planAPiece and planBPiece
	// to get the piece.  But genDestTmpCommon is used for both
	// planA and planB, so it often uses the wrong piece for the wrong
	// plan.  Still, it is better to sometimes use the wrong piece
	// than to base win/loss assumptions just on rank.

		Piece attackPiece = null;
		if (attackRank != Rank.NIL) {
			attackPiece = planAPiece[1-color][attackRank.ordinal()-1];
			if (attackPiece == null)
				attackPiece = planBPiece[1-color][attackRank.ordinal()-1];
			if (attackPiece == null) 
				return null;
		}
			
		int[] destTmp = new int[121];
		for (int j = 0; j <= 120; j++)
			destTmp[j] = DEST_VALUE_NIL;

		destTmp[to] = 1;
		ArrayList<Integer> queue = new ArrayList<Integer>();
		queue.add(to);
		int count = 0;
		while (count < queue.size()) {
			int j = queue.get(count++);
			if (!Grid.isValid(j))
				continue;
			int n = destTmp[j];

			Piece p = getPiece(j);

		// Any piece blocks the way
		// (except for the destination piece, if any).
		// (I had tried to allow a path through 
		// pieces of opposite "color" to have a 1 move penalty,
		// because the attacker can move these out of the way,
		// but this causes bunching up of attackers).

			if (p != null
				&& !(j == to && p.getColor() == color)

		// If the caller is invincible or the Spy,
		// then the maze continues through non-invincible moved pieces.
		// The hope is that the lesser moved pieces
		// will eventually move.  This should not cause
		// stacking problems when there are few invincible
		// pieces.  This tries to address the situation where
		// the invincible piece is known and the opponent
		// targets the players moved pieces.  The player
		// is targeting the opponents invincible piece, but it
		// needs to see through moved pieces.

				&& (attackRank == Rank.NIL
					|| !(p.hasMoved()
						&& !isInvincible(p)
						&& guarded == GUARDED_CAUTIOUS)))
				continue;

		// check for guarded squares
			if ((guarded == GUARDED_CAUTIOUS
				|| guarded == GUARDED_UNKNOWN
				|| guarded == GUARDED_OPEN_CAUTIOUS) && j != to) {
				boolean isGuarded = false;
				for (int d : dir) {
					int i = j + d;
					if (!Grid.isValid(i))
						continue;
					if (i == to)
						continue;
					Piece gp = getPiece(i);
					if (gp == null 
						|| gp.getColor() != color)
						continue;

					if (guarded == GUARDED_CAUTIOUS || guarded == GUARDED_OPEN_CAUTIOUS) {
						int result = winFight(gp, attackPiece);
						if (result == Rank.WINS
							|| result == Rank.UNK)
							isGuarded = true;
					} else if (!gp.isKnown())
						isGuarded = true;
				}
				if (isGuarded) {
					destTmp[j] = DEST_VALUE_NIL;
					continue;
				}
			}

			// set the neighbors
			for (int d : dir) {
				int i = j + d;
				if (!Grid.isValid(i) || destTmp[i] != DEST_VALUE_NIL)
					continue;

				destTmp[i] = n + 1;
				queue.add(i);
			} // d
		} // while
		return destTmp;
	}

	private int[] genDestTmp(int guarded, int color, int to)
	{
		return genDestTmpCommon(guarded, color, to, Rank.NIL);
	}

	private int[] genDestTmpGuardedOpen(int color, int to, Rank guard)
	{
		return genDestTmpCommon(GUARDED_OPEN_CAUTIOUS, color, to, guard);
	}

	// Used by invincible pieces and the Spy, which move towards opponent pieces
	// even if blocked by pieces of their own color
	private int[] genDestTmpGuarded(int color, int to, Rank guard)
	{
		return genDestTmpCommon(GUARDED_CAUTIOUS, color, to, guard);
	}

	private void genNeededPlanA(int neededNear, int [] desttmp, int color, int rank, int priority)
	{
		genPlanA(neededNear, desttmp, color, rank, priority);
		if (!hasPlan(planAPiece, color, rank))
			setNeededRank(color, rank);
	}

	private void genNeededPlanB(int [] desttmp, int color, int rank, int priority)
	{
		genPlanB(desttmp, color, rank, priority);
		setNeededRank(color, rank);
	}

	private void genPlanA(int [] desttmp, int color, int rank, int priority)
	{
		setPlan(planA[color][rank-1], desttmp, priority);
	}

	// The value of the destination trails off with distance.
	// This encourages the piece to prefer closer destinations.

	public static int log2nlz( int bits )
	{
	    if( bits == 0 )
		return 0; // or throw exception
	    return 31 - Integer.numberOfLeadingZeros( bits );
	}

	private static int trailing(int n)
	{
		if (n == DEST_VALUE_NIL)
		 	return n;
		return log2nlz(n);
	}

	private void setPlan(int[][] plan, int[] tmp, int priority)
	{
		if (tmp == null)
			return;

		assert tmp[0] == DEST_VALUE_NIL : "call genDestTmp before setPlan";
		for (int j = 12; j <= 120; j++)
			if (plan[1][j] > priority) {
				if (plan[0][j] == DEST_VALUE_NIL) {
					plan[0][j] = tmp[j];
					plan[1][j] = priority;
				}
			} else if (plan[1][j] < priority) {
				if (tmp[j] != DEST_VALUE_NIL) {
					plan[0][j] = tmp[j];
					plan[1][j] = priority;
				}
			} else if (plan[0][j] > tmp[j])
				plan[0][j] = tmp[j];
	}

	private void setPlan(int neededNear, int[][] plan, int[] desttmp, int color, int rank, int priority)
	{
		if (desttmp == null)
			return;

		if (neededNear != 0) {
			int[]tmp = new int[121];
			if (!hasPlan(planAPiece, color, rank))
				setNeededRank(color, rank);

			// deter aimless chasing of the target piece, because
			// otherwise it will guess the chaser's rank

			for (int j = 0; j <= 120; j++) {
				if (desttmp[j] == 2)
					tmp[j] = 5;
				else tmp[j] = desttmp[j];
			}

			setPlan(plan, tmp, priority);
		} else
			setPlan(plan, desttmp, priority);
	}

	private void genPlanB(int [] desttmp, int color, int rank, int priority)
	{
		setPlan(planB[color][rank-1], desttmp, priority);
	}

	private void genPlanB(int neededNear, int [] desttmp, int color, int rank, int priority)
	{
		setPlan(neededNear, planB[color][rank-1], desttmp, color, rank, priority);
	}

	private void genPlanA(int neededNear, int [] desttmp, int color, int rank, int priority)
	{
		setPlan(neededNear, planA[color][rank-1], desttmp, color, rank, priority);
	}

	protected boolean hasAdjacentUnknownEnemy(int color, int to)
	{
		for (int d : dir) {
			Piece tp = getPiece(to + d);
			if (tp == null
				|| tp.getColor() != 1 - color)
				continue;
			if (tp.getApparentRank() == Rank.UNKNOWN)
				return true;
		}
		return false;
	}

	// suspectedRank is based on ActingRankChase.
	// If the piece has chased another piece,
	// the ai guesses that the chaser is a lower rank
	// If there are no lower ranks, then
	// the chaser may be of the same rank (or perhaps higher, if
	// bluffing)

	protected void genSuspectedRank()
	{
		super.genSuspectedRank();

		lastMove = getLastMove(1);
		if (lastMove != null) {
			lastMovedPiece = getPiece(lastMove.getTo());
			if (lastMovedPiece != null
				&& lastMovedPiece.getColor() == lastMove.getPiece().getColor()) {

		// set actingRankFlee temporarily on any AI pieces approached
		// by an opponent piece.  When the AI evaluates any
		// move other than moving the approached piece,
		// the opponent will realize that the approached piece
		// is weak.

				boolean hasProtector = false;
				for (int d : dir) {
					Piece tp = getPiece(lastMovedPiece.getIndex()+d);
					if (tp == null)
						continue;
					if (tp.getColor() == Settings.bottomColor) {
						if (!tp.isKnown()
							|| tp.getRank().ordinal() < lastMovedPiece.getRank().ordinal() - 1) {
							hasProtector = true;
							break;
						}
					}
				}

				if (!hasProtector)
				for (int d : dir) {
					Piece tp = getPiece(lastMovedPiece.getIndex()+d);
					if (tp == null)
						continue;
					if (tp.getColor() == Settings.topColor)
						tp.setActingRankFlee(lastMovedPiece.getRank());
				}
			}
		}
	}

	// An expedition into the unknown and unmoved area of the
	// opponents ranks is called a foray.  
	// The idea behind a foray is to discover low ranked pieces
	// and win higher ranked pieces while avoiding Bombs.
	//
	// Statistically, random forays result in a loss of material,
	// if the opponent has strategically placed its pieces to rebuff them.
	// So a foray must be a directed attack in only one of the lanes,
	// preferably the side lanes, so that the opponent cannot
	// easily refortify the area.  Indeed, it is counter-productive
	// to attack multiple lanes at once, because the more the
	// opponents pieces move, the more mobility the opponent
	// has to refortify the other lanes.
	//
	// Humans are often able to guess the ranks of unmoved pieces
	// and direct forays accordingly, resulting in material gain.
	// This ability needs to distilled into an algorithm giving
	// the AI the same advantage.

	protected void genForay()
	{
		if (forayLane == -1) {
			// Choose the foray lane.
			int maxPower = -3;
		for (int lane = 0; lane < 7; lane += 3) {

		// For now, no forays in the middle lane
		// TBD: randomly allow them

			if (lane == 3)
				continue;

			int power = 0;
			for (int y = 2; y < 4; y++)
			for (int x = 0; x < 4; x++) {
				int i = Grid.getIndex(x + lane, y);
				Piece p = getPiece(i);
				if (p == null
					|| p.getColor() == Settings.bottomColor)
					continue;

		// Avoid pushing pieces and leaving bombs behind
		// because then the bombs become obvious 

				if (!p.isKnown()
					&& p.getRank() == Rank.BOMB)
					power-=3;

		// The One and the Spy do not foray and cannot be exposed

				else if (p.getRank() == Rank.ONE
					|| p.getRank() == Rank.SPY)
					power-=3;

		// Eights do not foray either

				else if (p.getRank() == Rank.EIGHT)
					power--;

		// Need some powerful pieces for the foray to succeed.
		// We are counting on having a superior rank advantage
		// in the area because we are going
		// to ignore any opponent attempts at bluffing.

				else if (p.getRank() == Rank.THREE)
					power++;
				else if (p.getRank() == Rank.TWO)
					power+=2;
			}
			if (power < maxPower)
				continue;
			forayLane = lane;
			maxPower = power;

			} // lane

			if (forayLane != -1)
				for (int y = 6; y < 10; y++)
				for (int x = 0; x < 4; x++) {
					int i = Grid.getIndex(x + forayLane, y);
					forayMap[i] = true;
				}
		}
		
		// Knowing the lowest unknown expendable rank is
		// useful in an encounter with an opponent piece that
		// has approached an AI unknown.  The AI assumes that
		// these piece are expendable, because an opponent
		// usually tries to avoid discovery of its lower ranks.

		lowestUnknownExpendableRank = 0;
		for (int r = 1; r <= 9; r++)
			if (unknownNotSuspectedRankAtLarge(Settings.bottomColor, r) > 0) {
				lowestUnknownExpendableRank = r;
				if (r >= 5)
					break;
			}

		if (lowestUnknownExpendableRank == 0
			|| (lowestUnknownExpendableRank < 5
			&& rankAtLarge(Settings.topColor, Rank.ONE) == 0)
			&& unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.SPY) > 0)
			lowestUnknownExpendableRank = 10;


		int f;
		f = unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.NINE);
		f += unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.EIGHT);
		f += unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.SEVEN);
		f += unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.SIX);
		f += unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.FIVE);
		f += unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.FOUR);
		f -= unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.THREE)*4;
		f -= unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.TWO)*2;
		f += unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.ONE)*4;
		f -= unknownRankAtLarge(Settings.bottomColor, Rank.BOMB)*3;
		f += isWinning(Settings.bottomColor)/VALUE_FIVE;
		foray[4] = (f > 2) || (dangerousUnknownRank < 4);

		foray[5] = ((unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.NINE)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.EIGHT)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.SEVEN)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.SIX)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.FIVE)
			- unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.FOUR)*3
			- unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.THREE)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.TWO)*2
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.ONE)*4
			- unknownRankAtLarge(Settings.bottomColor, Rank.BOMB)
			+ isWinning(Settings.bottomColor)/VALUE_FIVE
			) > 10)
				|| (dangerousUnknownRank < 5);

		// One exception to the rule that the AI piece loses its value
		// in an unknown exchange is at the start of the game when
		// few of the pieces can move and most of the expendable
		// pieces are still unknown.  This condition is slightly
		// favorable to a kamikaze foray by a Six, because 
		// winning a random encounter is greater than 50%
		// even against an unmoved piece.
		//
		// A Six has to win only one such encounter.
		// Because the opponent often places
		// its higher ranks in the front line and bombs in the
		// rear row, odds are improved for a foray into the front row.
		//
		// The probability is hard to prove theoretically
		// (because it depends on opponent setup) so
		// one must run a series of games to prove that this
		// is a valid rule.  And if the AI always uses its
		// Sixes to attack, then the opponent will learn this,
		// and simply place Fives in the front row to rebuff them.

		// One can reason this intuitively as follows.
		//
		// A Six loses against 14 pieces (Bombs, 4s and 5s).
		// Note that even if it wins only one piece and loses the
		// next, it will also have won the stealth value of the
		// attacker.  The probability of winning one piece is
		// about 60%.

		foray[6] = ((unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.NINE)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.EIGHT)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.SEVEN)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.SIX)
			- unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.FIVE)*2
			- unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.FOUR)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.THREE)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.TWO)*2
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.ONE)*4
			- unknownRankAtLarge(Settings.bottomColor, Rank.BOMB)
			+ isWinning(Settings.bottomColor)/VALUE_FIVE
			) > 3)
				|| (dangerousUnknownRank < 6);
		if (foray[6] && lowestUnknownExpendableRank == 5)
			lowestUnknownExpendableRank = 6;

		foray[7] = ((unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.NINE)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.EIGHT)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.SEVEN)
			- unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.SIX)*2
			- unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.FIVE)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.FOUR)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.THREE)
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.TWO)*2
			+ unknownNotSuspectedRankAtLarge(Settings.bottomColor, Rank.ONE)*4
			- unknownRankAtLarge(Settings.bottomColor, Rank.BOMB)
			+ isWinning(Settings.bottomColor)/VALUE_FIVE
			) > 3)
				|| (dangerousUnknownRank < 7);

		// The AI prefers to retain its Scouts for bluffing or
		// for directed attack on suspected ranks,
		// but if the AI never uses its Scouts for random attack,
		// then the opponent would simply place its low ranked
		// pieces in the front lines.
		//
		// So there is always a chance that the AI will make
		// a random Scout attack, although the probability
		// diminishes greatly with the number of its remaining Scouts.

		int i;
		for (i = 9 - rankAtLarge(Settings.topColor, Rank.NINE); i > 0 && rnd.nextInt(4) == 0; i--);
		
		if (i == 0
			|| rankAtLarge(Settings.topColor, Rank.NINE) > 4)
			foray[9] = true;

	}

	public boolean hasPlan(Piece plan[][], int color, int r)
	{
		return plan[color][r-1] != null;
	}

	public int isWinning(int color)
	{
		return sumValues[color] - sumValues[1-color];
	}

	// Sixes, Sevens and Nines (and excess known Eights) are expendable
	//
	// Note: values[] is used deliberately instead of pieceValue()
	// because the value of a Six can be reduced but the stealth
	// values do not change, so in reduced piece situations a Nine
	// (with high stealth) would no longer be expendable.
	public boolean isExpendable(int c, int r)
	{
		return values[c][r] <= values[c][6];
	}

	public boolean isExpendable(Piece p)
	{
		return isExpendable(p.getColor(), p.getRank().ordinal());
	}

	public void setNeededRank(int color, int rank)
	{
		neededRank[color][rank-1] = true;
	}

	protected int planv(int [][] plan, int from, int to)
	{
		int vto = plan[0][to];
		int vfrom = plan[0][from];
		int priority = plan[1][from];

		if (plan[1][to] == priority) {

			if (vto == DEST_VALUE_NIL
				|| vfrom == DEST_VALUE_NIL)
				return 0;

		// The difference in chase plan values for adjacent squares
		// is always 1, except if the plan was modified by
		// neededNear, which makes the squares
		// adjacent to the target (value 2) to be value 5.
		//
		// 4 3 4 5	4 3 4 5
		// 3 2 3 4	3 5 3 4
		// 2 T 2 3	5 T 5 3
		// 3 2 3 4	3 5 3 2
		// chase	neededNear
		//
		// Note: adjacent squares in flee plans are not restricted
		// to a difference of 1 (could be 0).

			if (priority >= DEST_PRIORITY_DEFEND_FLAG_AREA && depth > 1)
				return 0;

			return (vfrom - vto) * priority;

		} // to-priority == from-priority

		else {

		// The goal of fleeing in attacklanes()
		// is to keep the higher ranked piece out of the lane.
		// Until version 10.0, fleeing was awarded more points
		// than chasing.  But the proximity pruning mechanism
		// then causes chases to be thwarted in the lane.
		// For example:
		// -- -- --
		// R2 -- --
		// -- -- xx
		// -- B3 xx
		// -- -- --
		// If R2 moves down, B3 may be close enough to R2 to allow
		// B3 to be considered, and if fleeing is awarded more than
		// chasing, R2 will not chase.  Even worse, R2 may move
		// away, because perhaps B3 would no longer be close enough for the
		// higher value B3 fleeing move to be considered.
		//
		// TBD: this effect likely occurs with other priority differences
		// as well.

			if (priority == DEST_PRIORITY_LANE)
				return DEST_PRIORITY_CHASE;
			else if (plan[1][to] == DEST_PRIORITY_LANE)
				return -DEST_PRIORITY_CHASE;
		}

		return 0;
	}

	// Give Plan A value to one piece of a rank
	// and Plan B value to the rest of the pieces
	// if the piece has moved or is known
	public int planValue(Piece fp, int from, int to)
	{
		int fpcolor = fp.getColor();
		int r = fp.getRank().ordinal() - 1;

		if (planAPiece[fpcolor][r] == fp
			|| planAPiece[fpcolor][r] == null && neededRank[fpcolor][r])
			return planv(planA[fpcolor][r], from, to);
		else if (fp.hasMoved()
				|| fp.isKnown()
				|| neededRank[fpcolor][r])
			return planv(planB[fpcolor][r], from, to);
		return 0;
	}

	// To be consistent, the remaining opponent piece after an attack is
	// assigned a suspected rank less than the attacker rank.
	//
	// This can help the ai protect another ai piece from an
	// unknown attacker, because if the attacker takes the first
	// ai piece, the ai has a good shot at recapture if the
	// attacker stays on the board with a known rank.
	// Of course, the attacker could be much stronger than predicted,
	// so the ai could make a serious error as well.
	//
	// If the AI attacks an unknown piece, it creates a known Unknown.
	// A known Unknown (isKnown() and isSuspectedRank() are both true)
	// has a suspected rank, so its value is diminished in
	// aiValue(), and it is also known, so a further attack on
	// the opponent piece does not garner its stealth value.
	//
	// Note: rankWon can be the Flag in which case the newRank
	// will be based on lowestUnknownExpendableRank.
	//
	public void makeWinner(Piece p, Rank rankWon, boolean aiAttack)
	{
		boolean isSuspected = p.isSuspectedRank();
		boolean isMaybeEight = p.getMaybeEight();
		Rank newRank = p.getRank();

		// The winner must always become known.
		// Otherwise the AI will plan to attack the same
		// piece multiple times to accumulate its stealth value.
		//
		// Note: makeKnown clears SUSPECTED_RANK and MAYBE_EIGHT
		// so these are reset below as needed
		p.makeKnown();

		// If the piece is an AI piece
		// or a known opponent piece (not suspected)
		// or a Nine (only can win a Spy)
		// or suspected Bomb or Flag (can win any piece),
		// the piece rank does not change.

		if (p.getColor() == Settings.topColor
			|| (newRank != Rank.UNKNOWN && !isSuspected)
			|| newRank == Rank.NINE
			|| newRank == Rank.BOMB
			|| newRank == Rank.FLAG)
			return;

		// The only piece that wins a bomb is an Eight,
		// and then it is no longer suspected
		if (rankWon == Rank.BOMB) {
			p.setRank(Rank.EIGHT);
			return;
		}

		// If the piece already has a suspected rank, keep it.
		// For example, if a Nine attacks a suspected One,
		// makeWinner(One, Nine) is a One rather than some
		// rank lower than Nine.

		if (!isSuspected) {
			assert newRank == Rank.UNKNOWN : "Rank is " + newRank + " but expected UNKNOWN to win " + rankWon;

			if (rankWon == Rank.FLAG)
				newRank = Rank.toRank(lowestUnknownExpendableRank);
			else if (rankWon == Rank.ONE) {
				newRank = Rank.SPY;
			} else
				newRank = getChaseRank(p, rankWon, false);

			if (newRank == Rank.NIL)
				newRank = Rank.toRank(lowestUnknownExpendableRank);

			assert newRank != Rank.UNKNOWN : "Piece " + p.getRank() + " " + p.isSuspectedRank() + " " + p.getActingRankChase() + " " + p.isKnown() + " should not have won (invincible)" + rankWon + " at " + p.getIndex() + " because lowestUnknownNotSuspectedRank is " + lowestUnknownNotSuspectedRank;
		}

		// Suspected ranks have much less value (see aiValue()).
		// The AI cannot attack a trapped low ranked opponent
		// piece with a losing piece just to increase its value.
		// So when the AI attacks an unknown opponent piece
		// that remains on the board, the opponent piece becomes
		// known, but remains a suspected rank.
		//
		// In addition, if an unknown opponent piece wins
		// an AI piece greater than a Six, the assumption
		// is that the attacker is a Five.  But it might also
		// be a lesser valued piece.  So suspected rank is
		// set in this case as well.

		if (aiAttack
			|| rankWon.ordinal() >= 6)
			p.setSuspectedRank(newRank);	// known Unknown
		else
			p.setRank(newRank);

		// Attacks on or by Nines do not change MAYBE_EIGHT
		if (rankWon.ordinal() >= 9)
			p.setMaybeEight(isMaybeEight);
	}

	// morph() is the same concept as makeWinner(), but for AI pieces.
	// It is used when a bluff is effective,
	// because the opponent would have to consider that its piece would
	// be removed from the board, and a superior known AI piece
	// would replace it.
	//
	// It is also necessary to avoid transposition cache equivalency
	// because the disappearance of a bluffing AI piece is not equivalent
	// to its disappearance in other situations (e.g. EVEN).
	// So keep the bluffing AI piece on the board to avoid this.

	public void morph(Piece p, Rank rankWon)
	{
		// TBD: morph into a lower rank
		p.makeKnown();
	}

	public void move(int m)
	{
		move(m, Grid.isAdjacent(m));
	}

	public void move(int m, boolean adjacent)
	{
		int from = Move.unpackFrom(m);
		int to = Move.unpackTo(m);
		Piece fp = getPiece(from);
		Piece tp = getPiece(to);
		boolean scoutFarMove = !adjacent;
		boolean unknownScoutFarMove = scoutFarMove && !fp.isKnown();
		moveHistory(fp, tp, m);

		if (depth == 0) {
			assert boardHistory[0].hash == hashTest[0] : "bug: hash changed before move "  + from + " to " + to;
			assert boardHistory[1].hash == hashTest[1] : "bug: hash changed before move "  + from + " to " + to;
		}

		clearPiece(from);
		int vm = 0;

		// Moving an unknown scout reveals its rank.

		if (unknownScoutFarMove) {
			fp.setRank(Rank.NINE);
		}

		Rank fprank = fp.getRank();
		int fpcolor = fp.getColor();
		boolean fpknown = fp.isKnown();
		boolean fpsuspected = fp.isSuspectedRank();

		int rank = fprank.ordinal();
		if (!fp.isKnown()
			&& fp.moves == 0)
			vm += unmovedValue[from];

		if (unknownScoutFarMove) {
			vm -= stealthValue(fp);
			fp.makeKnown();
		}

		if (tp == null) { // move to open square

		// If an unknown opponent piece approaches an unknown AI piece
		// during the search, the opponent piece is probably
		// weak, so it gains a temporary chase rank so that deeper
		// evaluation is consistent.  For example,
		// RS --
		// R3 --
		// -- xx
		// B? xx
		// All pieces are unknown.  When the AI considers the
		// move sequence beginning with B? moving up towards
		// unknown Red Three, unknown Blue gains a chase rank
		// of unknown, so Red can plan to play R3xB? with the
		// expectation of a WIN.
		//
		// Note that without this code, unknown Blue would be
		// a threat to Red Three, so the move sequence would
		// be very negative after Red Three flees and unknown
		// Blue forks Red Spy and Red Three.
		//
		// Note that if a *known* AI piece is approached during
		// the search, the opponent piece is still unknown
		// and does not gain a suspected rank, because suspected
		// ranks are carefully assigned based on actual opponent play
		// and position analysis.
		//
		// Thus in the example above, if Red Three was known,
		// unknown Blue is a significant threat.  If unknown
		// Blue approaches, the AI does not know if unknown Blue
		// is a Two or just a bluffer.  So Red Three must move
		// aside and Blue then forks Red Spy and Red Three.
		// Red Three must retreat again, which would allow the
		// bluffer to capture the Spy.
		//
		// TBD: This is why it is important that is is best to
		// assign suspected ranks to unknown pieces 
		// before rather than during the search.  The AI
		// must guess the opponent unknown ranks and then live 
		// or die by its analysis, even if incorrect.  Otherwise
		// a bluffing opponent can quickly control the game.
		//
		// Thus in the example above, if unknown Blue had been
		// assigned a suspected Rank of Two, then Red would not
		// be imperiled, because unknown Blue would likely not
		// approach the unknown Spy, because it could be the
		// unknown One.

			if (fp.getApparentRank() == Rank.UNKNOWN
				&& fp.getActingRankChase() != Rank.UNKNOWN
				&& grid.hasAttack(fpcolor, to)) {

				if (hasAdjacentUnknownEnemy(fpcolor, to)) {
					fp.setActingRankChaseEqual(Rank.UNKNOWN);

		// By approaching an unknown, the piece divulges
		// its identity as a weak piece, so this is a bad idea
		// unless it has a material reason to do so.

					vm -= DEST_PRIORITY_CHASE_ATTACK;
				}
			}

			UndoMove m2 = getLastMove(2);
			if (m2 != null && m2.getTo() == to) {
				Rank m2fprank = m2.getPiece().getRank();

		// If the AI moves an unknown piece adjacent to
		// an opponent known piece of the same rank, then
		// if the opponent attacks the AI piece, it is an EVEN
		// exchange but the opponent normally wins the AI piece stealth.
		// However if the opponent piece is not invincible, the AI
		// does not award the stealth, because the opponent may
		// not realize that the AI is bluffing.  But if the
		// opponent piece is an invincible One, a defending
		// unknown AI piece *could* be the Spy, so an unknown
		// AI piece moving to this square is credited with
		// restoring the lost stealth and adding the bluffing value.
		// (very rare occurrence).

				if (fpcolor == Settings.topColor
					&& m2fprank == Rank.ONE
					&& !m2.tp.isKnown()
					&& !fp.isKnown()
					&& hasSpy(Settings.topColor))
					vm += stealthValue(m2.tp) - valueBluff(m2.tp, fp);
			}

		// Because each move towards a chase piece is rewarded,
		// at higher plies, the AI may leave low valued material
		// hanging so that the opponent cannot unrelentingly chase
		// the vulnerable piece, preventing the AI from accumulating
		// its chase points.  So flee moves are rewarded as well.
		//
		// Note: this should discourage back-and-forth chases,
		// because the chased piece keeps gaining points
		//
		// Note: DEST_PRIORITY_CHASE_ATTACK is enough to take
		// the wind out of the sails of any back-and-forth chase,
		// including that by an invincible piece.

			if (m2 != null && Grid.isAdjacent(from, m2.getTo()))
				vm += DEST_PRIORITY_CHASE_ATTACK;

			int v = planValue(fp, from, to);

		// Scouts go too fast, so limit the points to one

			if (scoutFarMove)
				v = Math.min(v, 1);

			vm += v;
			fp.moves++;
			setPiece(fp, to);

		} else { // attack

		// Remove target piece and update hash.
		// (Target will be restored later, if necessary,
		// perhaps with different hash because of
		// change in "known" status and perhaps rank).

			clearPiece(to);

			Rank tprank = tp.getRank();

		assert !(fp.isKnown() && fprank == Rank.UNKNOWN
			|| tp.isKnown() && tprank == Rank.UNKNOWN)
			: "Entry " + fprank + "X" + tprank;

		// note: use actual value for ai attacker
		// because unknown ai attacker knows its own value
		// but opponent attacker is just guessing

			int fpvalue = actualValue(fp);

		// An attack on an unmoved (unknown) piece
		// gains its unmoved value to help avoid draws.
		// This encourages mindless slamming into
		// suspected bomb structures with the likely result of
		// loss of the piece, so limit this to expendable rank.
		// The idea is that if it isn't a bomb, then the
		// number of possible structures is reduced to better
		// target the Eights.  The AI only does this if it
		// thinks it is winning and wants to speed up the game.

		// Version 9.2 no longer gives a bonus to attack
		// a piece with an unmovedValue, because these pieces
		// are likely bombs.

		// TBD: give more thought about what pieces to attack
		// if the AI is winning and the opponent is stalling.
		// If the opponent has moved many of its pieces, it
		// is better to attack those, but if the opponent
		// has kept its pieces unmoved, it is better to attack those.
		// These attacks should be conceived to allow a
		// lower ranked AI piece in the area to profit from
		// the discovery in some way.

		//	if (tp.moves == 0 && isExpendable(fp))
		//		vm += unmovedValue[to];

			int result = winFight(fp, tp);

			switch (result) {
			case Rank.EVEN :
				// assert fprank != Rank.UNKNOWN : "fprank is unknown?";
		// If the defender is an AI piece and the
		// attacker is unknown OR the defender is an unknown
		// opponent piece, the AI is just guessing the opponent's
		// rank, and it is guessing that it could be even.
		// However, the AI usually overstates the strength of
		// the opponent to be safe, and the opponent piece is more
		// likely a less strong piece.
		//
		// Thus, the result should be positive for the AI.  Yet if the 
		// AI attacker is unknown (the opponent piece is
		// also unknown at this point in the code), and
		// if the opponent piece turned out to be a bluffing
		// high ranked piece, the AI would lose stealth
		// and gain very little, which could cause the result
		// to be negative.
		//
		// The AI stands to gain more from an attack
		// the lower the suspected rank.  This is because multiple
		// ranks map into a lower suspected rank, so an
		// EVEN exchange is more likely to WIN.
		//
		// The AI stands to lose more the higher the suspected rank
		// because higher ranks tend to LOSE.  The worst
		// case is the Spy.  The AI should not play SxB?
		// nor allow B?xS,
		// even if unknown Blue is a suspected Spy.
		// 
		// A known Unknown is also usually overstated in rank.
		// For example:
		// -- RB --
		// xx R9 R5
		// xx B? --
		// All pieces are unknown.  Red has the move.
		// Red errs by moving Red Five away.  Why?  The AI
		// assumes that unknown Blue is the lowest expendable
		// piece, or a Five.  So it sees the loss of Red Nine
		// as inevitable.  But if Blue turns out to be a Six or
		// Seven, then Red Five would regain the exchange.

				if (tp.getColor() == Settings.topColor) {

		// If the opponent piece is unknown,
		// the AI mave have guessed wrong,
		// and the exchange could be a loss.  This case
		// should only happen at depth, because if
		// the opponent actually allows its piece to contact
		// the known AI piece, the opponent would gain
		// a lower chase rank and thus the exchange would not be EVEN.

		// Yet the AI piece should try to avoid this situation,
		// because of the possibility that it has guessed wrong,
		// but it cannot overreact and lose material to an idle threat.
		// For example:
		// xx -- R3 xx
		// xx -- R4 xx
		// -- -- B? --
		// Unknown Blue approaches known R3.  This makes Blue
		// a suspected rank of 3.  Red Four should move,
		// because Blue approaching known Red Three 
		// is barely negative (this EVEN case) as it is not a foregone
		// conclusion; Blue might not approach Red Three,
		// although that would be a very good move, because
		// then Red Three would have to move away (because
		// Blue is now a suspected Two), leaving Blue to
		// capture Red Four.

					if (!fp.isKnown()) {
						vm -= stealthValue(Settings.bottomColor, tprank.ordinal());

		// While the AI always gains the stealth value of the
		// unknown opponent piece, it may have guessed wrong.
		//
		// Note: the bluffing factor (br) changes the outcome
		// because it affects both stealth and riskOfLoss.
		// With lower bluffing factors (i.e. opponent not bluffing),
		// the result becomes more positive, because although the AI
		// has less knowledge to gain from an even exchange,
		// it has less chance of complete loss of its piece.
		//
		// Capture, Opp. Stealth (br=4), Risk Of Loss Factor, Result
		// 4x4?, 12, 16, -4 
		// 3x3?, 28, 33, -5
		// 2x2?, 32, 66, -34
		//
		// Note: riskOfLoss can be zero if the suspected rank fled.

						vm += riskOfLoss(tp, fp)/6;
					} // fp is unknown

		// If the defender is known, and either the attacker is known
		// or the AI is the attacker (AI knows its own pieces)
		// then, this is a known even exchange based on actual values.

		// Note that although the exchange is even, the value
		// may not be.  If the AI is winning, its ranks are
		// worth less than opponent ranks, encouraging it to
		// make an even exchange.

					// fp is known

					else if (tp.isKnown())
						vm += actualValue(tp) - fpvalue;

					else { // tp is unknown

		// If the attacker is invincible, then the attacker knows
		// that it cannot lose the exchange, but because this
		// exchange is even, tprank must also be the same rank,
		// so this is a known evenly valued exchange,
		// unless the AI piece is unknown (because of stealth).
		//
		// However, if the AI piece is unknown, the opponent piece
		// is known and the AI is winning, the AI must try to
		// neutralize the attacker's invincible pieces,
		// because otherwise they just run amok obliterating
		// all of the AI's moved pieces or simply amble about
		// until time runs out. So the AI deems this
		// as an even exchange, even if the AI piece still has stealth.
		//
		// If the AI is losing, it may be better for the AI to flee
		// and hope for a tie by time expiration.  But this makes
		// for boring games, and the AI still has confidence that
		// it could win, in spite of that it may be losing.  And the
		// isWinning() function isn't perfect, because it only looks
		// at material value.
		//
		// Note that suspected invincible pieces (except the One,
		// if the AI has the Spy) must also
		// be neutralized, although this can lead to loss
		// of stealth if the opponent is bluffing, or even
		// loss of the AI piece, if the opponent rank is
		// lower than suspected.

						if (isInvincible(fp)
							&& !(fprank == Rank.ONE && hasSpy(Settings.topColor)))
							vm -= 10;

		// If the defender is an unknown AI piece,
                // then an attacker (not an invincible attacker, see above)
                // doesn't really know that the exchange is even.
                // The attacker sees only an unknown gain
                // but the potential loss of its piece.
                //
                // Yet this result relies on an effective bluff.
                // If the exchange occurs in the flag area, for example,
                // the attacker may attack anyway.
                //
                // Furthermore, if it is not an effective bluff,
                // then R?xB must equal BxR?, because both pieces
                // are removed from the board, so the transposition
                // table will return an identical result regardless
		// of the direction of attack.
		//
		// (If it is an effective bluff, the bluffing
		// piece survives, and thus the transposition table
		// returns a correct result).

						else
							vm += actualValue(tp) - fpvalue;
					}

		// Unknown AI pieces also have bluffing value

					if (depth != 0
						&& !maybeIsInvincible(fp)
						&& isEffectiveBluff(tp, fp, m)) {
						vm = Math.min(vm, valueBluff(fp, tp));
						morph(tp, fprank);
						setPiece(tp, to);
					}

		// AI is attacker

				} else {

		// If the defender is known,
		// this is a known even exchange based on actual values
		// (AI knows its own pieces).

		// Note that although the exchange is even, the value
		// may not be.  If the AI is winning, its ranks are
		// worth less than opponent ranks, encouraging it to
		// make an even exchange.

				if (tp.isKnown()) {
					if (isInvincible(tp)
						&& !fp.isKnown()
						&& !(tprank == Rank.ONE && hasSpy(Settings.topColor)))
						vm += 10;
					else
						vm += actualValue(tp) - fpvalue;
				} else {

					assert !tp.isKnown() : "defender is known?"; 
					vm += stealthValue(tp) - stealthValue(fp);

		// Often an unmoved piece acquires a chase rank
		// because it protected a piece.  However, the piece
		// could have been cornered, and the protector is a bomb.
		// So if the piece could be a bomb,
		// then the AI loses its value, even if it thinks it
		// might be an even exchange.

					if (isPossibleBomb(tp)
						&& fprank != Rank.EIGHT)
						vm -= values[fpcolor][fprank.ordinal()];

		// The AI One risks only its stealth value in
		// an even exchange, if the opponent piece turns out
		// not be a one. If the One is known, the exchange
		// is a wash.

		// But other pieces risk total loss, if the AI has
		// has incorrectly guessed the defender rank.
		// While probability favors the AI, it does not
		// want to risk its lower ranked pieces on its guesses.

					else {
						if (fprank != Rank.ONE
							&& fprank.ordinal() <= 5)
							vm -= riskOfLoss(fp, tp);

		// Yet the AI usually is conservative,
		// so even exchanges usually turn out to be positive,
		// depending on the difference in values[]
		// (e.g. an even exchange of Eights could vary
		// wildly depending on the value of the Eights)

						vm += values[1-fpcolor][fprank.ordinal()] - values[fpcolor][fprank.ordinal()];

						vm += (9 - fprank.ordinal());
					}

		// Consider the following example.
		// -- R1 --
		// BS -- B2
		//
		// Blue Spy and Two are unknown and Red One is known.  Blue Two
		// has a suspected rank of One (because it chased Red Two).
		// Blue Two moves towards Blue Spy.  R1xB2 removes both
		// pieces from the board because the AI thinks that Blue Two
		// is Blue One.  Therefore it does not see BSXR1 and thus
		// loses the game.
		//
		// It is unlikely that unknown Blue One would have moved to
		// attack known Red One because the value of an unknown One
		// is much higher than a known One.  Although B2 is suspected
		// to be a One elsewhere on the board, if it moves to attack
		// Red One, Red must assume that it is a lower ranked piece
		// protected by a Spy.
		//
		// The same example with different ranks:
		// -- R3 --
		// B2 -- B4
		//
		// Blue Four has a suspected rank of Three.
		// R3xB4 would remove the pieces from the board
		// and the AI would not see B2xR3.
		//
		// By leaving the AI piece on the board, it can make
		// the following error:
		// R8
		// B? BB
		// BB BF BB
		// unknown Blue is suspected to be an Eight.  R8xB? would
		// leave the Red Eight on the board in position to attack
		// the flag.

		// In versions 9.3, the AI piece (other than an Eight)
		// stayed on the board if it attacks a suspected rank
		// in an EVEN exchange, because the suspected rank might
		// actually be higher.
		//
		// But this created the following undesired transposition
		// cache equivalency:
		// R1 RB
		// R3 --
		// -- B3
		// Red Three and Red Bomb are known and Blue Three
		// is suspected. 
		// ...    B3-a6
		// R3xB3  (null)
		// is very negative because perhaps B3 is a Two.
		//
		// ....   B3-b5
		// R3-a6  R3xRB
		// results in the same position and the cache returns
		// the negative number stored, rather than a highly
		// positive number because R3 just rammed into a known bomb!
		//
		// (Note: this issue also exists in a much reduced
		// form even in WINS. If the pieces are known, there
		// is no bug, but removing a suspected
		// opponent piece to a known bomb is not the same as removing
		// a suspected opponent piece in an exchange with a
		// known AI piece, but at least both are positive.)
		//
		// The solution is to remove both pieces from the
		// board but debit if there is a protecting opponent piece.
		//
		// Note: It is tempting to credit the opponent move,
		// but the opponent move will likely be forward pruned,
		// because the AI piece is gone!

					for (int d : dir) {
						Piece p = getPiece(to+d);
						if (p == null
							|| p.getColor() != Settings.bottomColor)
							continue;

						if (isThreat(p, fprank)) {
							vm -= values[Settings.topColor][fprank.ordinal()]/2;
							break;
						}
					}

				} // tp is not known
		// Unknown AI pieces also have bluffing value

				if (depth != 0
					&& (!isInvincible(tp)
						|| (tprank == Rank.ONE
							&& hasSpy(Settings.topColor)))
					&& !unknownScoutFarMove
					&& isEffectiveBluff(fp, tp, m)) {
						vm = Math.max(vm,  valueBluff(m, fp, tp) - valueBluff(tp, fp));
						morph(fp, tprank);
						setPiece(fp, to);
				}

				} // AI is attacker

		// If the opponent attacker is invincible and is
		// attacking an unknown AI piece where the outcome
		// happens to be even, the opponent actually does not know
		// the outcome will be even, and will expect that
		// its invincible piece will survive.  For example,
		// R? R? B1
		// 
		// Blue One should not attack unknown Red if the Spy is
		// still on the board, regardless of whether unknown
		// Red happens to be a One, because it expects that
		// the Blue One will survive.
		//
		// However, if Blue One is cornered, it may attack
		// unknown Red One, which is a serious loss to the AI.

		//		if (!tp.isKnown()
		//			&& tp.hasMoved()
		//			&& fpcolor == Settings.bottomColor
		//			&& isInvincible(fp)) {
		//			makeKnown(fp);
		//			setPiece(fp, m.getTo());
		//		}
				//vm = 0; // fubar
				break;

			case Rank.LOSES :

				if (fpcolor == Settings.topColor) {
					vm += stealthValue(tp);
		
		// TBD: If the target piece has a suspected rank, then
		// the ai is just guessing that it loses, so
		// there is a small chance that it could win.
		// The upside is maybe 10% of its value.  Larger than this,
		// the more likely the AI is to make bad exchanges.
		//
		// To be consistent, an attack
		// on a suspected rank that loses an AI piece
		// needs to be more value.  For example,
		// -- R? R?
		// B? R4 R?
		// -- xx xx
		//
		// Red Four is cornered by a piece chasing it.  R4xB?(3)
		// needs to be worse than staying pat.  After B?(3)xR4,
		// Blue becomes a known Three.
		// R?x3 still needs to be a deterrent.

		// So fp loses its entire value if known, but
		// nothing if the defender may think that it is
		// a lower ranked piece.  The value
		// of an unknown protector piece is irrelevant.
		//
					if (depth != 0
						&& (!isInvincible(tp)
							|| (tprank == Rank.ONE
								&& hasSpy(fpcolor)))
						&& !unknownScoutFarMove
						&& isEffectiveBluff(fp, tp, m)) {
						vm = Math.max(vm, valueBluff(m, fp, tp) - valueBluff(tp, fp));
						morph(fp, tprank);
						setPiece(fp, to);
					} else {

		// The opponent knows that the AI is bluffing, so
		// the opponent expects that whatever the AI piece rank,
		// it is lost.

		// Bombs could have a zero value, so when
		// the AI considers Bx?, it must return a realistic
		// negative value like any other AI piece.  Fx? must also
		// return a realistic value rather than its flag value,
		// because Fx? is not a move the AI can make.  This is
		// important in the case where tp is invincible,
		// because all attacks on the invincible piece are futile
		// and therefore is not deterred in passing an unknown AI
		// piece, even if the opponent has stealth.

						if (fprank == Rank.BOMB || fprank == Rank.FLAG)
							vm -= pieceValue(fpcolor, unknownRank[fpcolor]);
						else
							vm -= fpvalue;

		// makeWinner is not called because the opponent
		// knows that the AI is bluffing, so an AI attack does
		// nothing to clarify the opponent rank.

						if (tp.isSuspectedRank()) {
							tp.setKnown(true);
							if (fprank.ordinal() < 9)
								tp.setMaybeEight(false);
						}

						setPiece(tp, to);
					}
				} else {

		// AI is defender

		// The AI guesses that certain opponent pieces are bombs.
		// A suspected opponent bomb attacking an AI piece
		// always LOSES, because winFight return LOSES when
		// both pieces have rank and a bomb is always higher in rank.
		// (An unknown AI bomb can move as well, and this is
		// also handled sent to LOSES. See winFight()).
		
		// But the AI could be wrong, and so
		// allows the suspected bomb piece to move or attack.
		// An Eight is allowed to approach suspected bombs and attack.
		// But for valuable ranks, this is a bit aggressive, because
		// because the suspected bomb could be a lower ranked piece,
		// and then the AI could unwittingly lose a valuable rank
		// and consequently the game.

		// Should the Eight be allowed to pass or attack a
		// suspected worthless bomb?  The prior code
		// made any suspected worthless bomb an unknown.
		// This prevents the Eight from passing it to get at the
		// bomb structure.  The new code puts the Eight
		// at risk, perhaps unnecessarily if there is some other
		// path to the bomb structure.  Thus the AI needs to
		// be relatively confident that the suspected bomb
		// really is a bomb before marking it suspected.
		// The AI also tries to retain one more Eight than
		// the number of bomb structures to insure against loss.
		// A player cannot waste pieces on trying to identify
		// worthless bombs.

		// Unknown pieces are also allowed to pass suspected bombs.
		// This may be necessary to allow the piece to attack
		// other pieces.  Passing a bomb entails a tiny penalty
		// to encourage the piece to pick a safer path if at
		// all possible.

					if ((fprank == Rank.BOMB || fprank == Rank.FLAG)
						&& !isInvincibleDefender(tp))  {
						if (tprank == Rank.EIGHT
							|| !tp.isKnown()
							|| isExpendable(tp)
							|| (tprank == Rank.ONE
								&& !isPossibleUnknownSpyXOne(tprank, fp)))
							vm += 1;

		// How risky is it for a known valuable piece to approach
		// a suspected bomb?  There is always some risk, but
		// a reward could make it a worthwhile gamble.
		// The AI is willing to risk a Four (but not a One
		// Two or Three) if it can capture an expendable piece.
		// The AI would risk a Three to capture a Five.
	
						else
							vm += pieceValue(tp)/7;
					} else {

		// fp is opponent, so opponent loses but gains the stealth
		// value of the AI piece and the AI piece becomes known.

						vm += apparentWinValue(fp, fprank, unknownScoutFarMove, tp, stealthValue(tp));
						vm += riskOfLoss(tp, fp);
						vm = vm / distanceFactor(tp, fp);
					}

					tp.makeKnown();
					vm -= fpvalue;
					setPiece(tp, to);
				}

				// vm = 0; // fubar
				break;

			case Rank.WINS:

		// A win of a suspected piece is much less valuable than
		// a win of a known piece.
		//
		// Example:
		// R8 R8 R1 RB
		// R4 -- -- --
		// RS R3 B?
		//
		// All pieces are unknown except for Red Three.
		// Unknown Blue has been chasing Red Three, so
		// now the AI thinks that it might be a One (but it
		// could also be a Two, or any piece if Blue is bluffing).
		// 
		// If Red Three stays put, the result is very
		// negative because a suspected One does not have much value,
		// so SpyxOne? (WINS) is much less than B?xThree (WINS).
		//
		// Consider if Red Three moves up towards
		// Red Eight.  If unknown Blue moves up towards unknown
		// Red One (or left towards Red Spy),
		// unknown Blue loses its chase rank which becomes
		// Unknown, due to its reckless behavior, leading one to
		// believe that it is a bluffing high rank piece.
		//
		// Thus the next move should be R3xB?, because the Red
		// Three will try to protect the stealth of Red One
		// (or Red Spy).
		// This would be a bad move if unknown Blue turned out
		// to be Blue One or Blue Two, but the AI assumes that
		// the opponent plays consistently, and if the opponent
		// is reckless, then it will ultimately lose anyway.
		//
				vm -= stealthValue(fp);

				if (fpcolor == Settings.topColor) {
					vm += actualValue(tp);

		// If a piece has a suspected rank but has not yet moved,
		// and an AI piece (except Eight) attacks it,
		// assume a loss (because it could still be a bomb).
		// This often happens when an opponent bluffs with
		// a protector piece that hasn't moved.

					if (tp.isSuspectedRank()
						&& tp.moves == 0
						&& fprank != Rank.EIGHT)
						vm -= fpvalue;
					else if (fprank != Rank.ONE)
						vm -= riskOfLoss(fp, tp);

		// Because of the loss of stealth, an AI win is often
		// negative.  Yet the opponent does not know this,
		// so the value must at least be the bluffing value.

					if (depth != 0
						&& isEffectiveBluff(fp, tp, m))
						vm = Math.max(vm, VALUE_BLUFF);

		// If unknown fp was protecting a known piece that tp won
		// on the prior move, the AI is debited an additional
		// amount to discourage it from protecting the piece,
		// because when a known attacked piece ceases to move, the
		// opponent will guess that the protector is a lower ranked
		// piece, thus losing its stealth without any gain.
		// It is better for the attacked piece to flee.  Only
		// when the fleeing piece is cornered should it stop
		// at the protector; the opponent then has to guess
		// whether the cornered piece is protected or not.
		//
		// But if the unknown piece approached the attacker,
		// the attacker cannot be sure which unknown piece
		// is stronger.  For example,
		// RS -- B1
		// -- R8 --
		// Red Spy and Red Eight are unknown.  Red Eight
		// moves up to approach Blue One.  If 1x8?, then Red is
		// awarded the full value of RSxB1, because Blue
		// cannot be sure which piece is Red Spy.
		//
		// Conversely, if Blue One moves down and
		// approaches unknown Red Eight, Red Spy must
		// not move towards Red Eight to protect it
		// (Red Eight earns a flee rank of One because it
		// ceased to attack).
		// 
		// So the AI only receives the value for the win
		// only if the lost piece was itself an effective bluffer.

					UndoMove m2 = getLastMove(2);
					if (depth != 0
						&& !fp.isKnown()
						&& m2 != null
						&& m2.tp != null
						&& !isEffectiveBluff(m2.tp, tp, m2.getMove())
						&& tp == m2.getPiece())
						vm = Math.min(vm, VALUE_BLUFF);

					fp.makeKnown();
				}

		// If the target is not moved nor known and the attacker
		// is not an Eight, the attacker loses some % of its value.
		// This is related to the risk that the attacker
		// is willing to accept by blindly slamming its piece
		// into an unknown unmoved defender, which could be a bomb.
		// If the attacking rank is low valued, such as a Six
		// Seven or Nine, the attacker is willing to accept complete
		// loss.  But the attacker is less willing to lose its
		// more valuable ranks on such attacks.
		//
		// This entices the ai to not move a piece subject to attack,
		// even though it knows the attacker would win (which
		// is why the move landed in WINS).
		// For example,
		// R? R? R? R?
		// -- -- R8 R?
		// -- B7 R9 R8
		// All pieces are unknown except for Blue Seven.  It
		// it quite likely that Blue Seven will attack unknown
		// Red Nine.  But this is not a given.  B7xR9 is not
		// worth a full unknown piece (which is equal to the 
		// minimum piece value, which is a Nine), because
		// Blue Seven has risk of hitting a bomb.  Blue Seven
		// might also head for some other target.
		//
		// The example becomes more complex if Blue Seven
		// is a Blue Eight instead.  Eights have no reluctance
		// to attack an unmoved piece (that does not attack them,
		// eliminating any bluffing possibility).  So B8xR9 is
		// worth the full unknown piece.  But the counter move
		// R8xB8 is what Blue Eight is worried about, because
		// unknown Red Eight might be a lower rank, thus making
		// for a negative evaluation after B8xR9 (WINS), R8xR8
		// (EVEN, but high negative value due to bluffing).

		// Note: The factor has to be more than 50% because
		// near the end game when the AI is looking for a flag
		// on the back row, the flag is worth the lowest rank.
		// So if this rank is a Four (100), the AI will risk its
		// Three (200).

		// If a suspected opponent piece *could* be an Eight,
		// the assumption is that might *not* an Eight.
		// winFight() returns WINS if the piece could be an Eight,
		// but here the piece loses some percentage of its value.
		// This is a risky assumption, but necessary to
		// prevent the AI from leaving moved pieces hanging
		// if it assumes that the suspected piece could be
		// an Eight and capture the Spy or Flag bombs.  Thus
		// a bluffing Eight is very dangerous, because it
		// may be allowed to approach the AI flag bombs with
		// impunity, if it acquires a low suspected rank,
		// because these ranks are generally not Eights.
		//
		// This behavior was deliberately added
		// in version 9.6.  The AI needs to abide by its
		// assignment of suspected rank and must not allow
		// the opponent to bluff the AI into thinking otherwise,
		// even at the cost of its flag.  AI flag defense must
		// be improved in other ways, and the assignment of
		// suspected rank must be improved.

				else {	// fp is bottomcolor


		// could be bomb

					if (isPossibleBomb(tp)
						&& (fp.isKnown() || fp.isSuspectedRank())
						&& fprank != Rank.EIGHT) {
						int risk = apparentRisk(fp, fprank, unknownScoutFarMove, tp);

		// If risk is low, the AI piece is usually safe from attack.
		// But if the attacker is pinned (facing a sure loss),
		// it will likely attack. 
		// For example,
		// R3 B2 --
		// -- R1 --
		// Red Three is unknown and unmoved.  Blue Two cannot
		// go right because of Two Squares.  It is forced to
		// take Red Three.
		//
						if (lastMove != null
							&& Grid.isAdjacent(from, lastMove.getTo())
							&& risk == 1)

		// Prior to version 9.6, VALUE_MOVED was returned.  But
		// if an invincible opponent piece was near a slew of unmoved
		// pieces, qs() accumulated VALUE_MOVED and so the AI
		// assumed that the opponent would attack the slew of
		// unmoved pieces, and so the AI would leave moved pieces
		// hanging as well.  So in version 9.6, the AI simply checks
		// to see if the opponent piece is attacked.  If not,
		// an attack on an unmoved AI piece is zero.

							vm = 0;
						else {
							vm -= fpvalue * (10 - risk) / 10;

							vm += apparentWinValue( fp,
								fprank,
								unknownScoutFarMove,
								tp,
								actualValue(tp));
							vm = vm / distanceFactor(tp, fp);
						}

					} else { // not a possible bomb

						vm += apparentWinValue(fp,
							fprank,
							unknownScoutFarMove,
							tp,
							actualValue(tp));
						vm = vm / distanceFactor(tp, fp);

		// Chase bluffs. Allow an expendable unknown AI piece to 
		// chase a low ranked opponent piece.  For example, unknown
		// Red Seven approaches Blue Two.  B2xB7? is a WIN, but 
		// valueBluff() makes it a small loss.

						if (depth != 0
							&& !maybeIsInvincible(fp)
							&& isEffectiveBluff(tp, fp, m)) {
							vm = Math.min(vm, valueBluff(fp, tp));
							morph(tp, fprank);
							setPiece(tp, to);
							break;
						}


					}

					makeWinner(fp, tprank, false);
				}


				fp.moves++;
				setPiece(fp, to); // won
				//vm = 0; // fubar
				break;

			case Rank.UNK:
		// note: fpvalue is actualValue

		// fp or tp is unknown

				if (fpcolor == Settings.topColor) {

		// AI IS ATTACKER (fp)

		// The lower the AI piece rank, the more likely it will win
		// against an unknown.  But lower ranked pieces have
		// higher value and therefore more risk.  The risk is not
		// as high as the actual piece value, if the opponent
		// has not guessed the piece rank, because the opponent
		// will not likely risk its superior pieces against unknowns.
		//
		// But there are situations where the opponent will weigh
		// the risk of taking an unknown piece.  In the diagram below,
		// all pieces are unknown.  Red 3 moves to the empty square
		// above Blue 2. Blue guesses that the Red 3 is probably not
		// not an unknown Red One, because Blue has already guessed
		// that Red One is some other piece.  Blue has also guessed
		// that Red 3 is acting like a low ranked piece.
		// So Blue Two takes Red 3 and wins.
		//
		// -- R3
		// B2 B8
		// 
		// This could also turn out to be loss if Red 3 turned out
		// to be a Red 9, because Blue 2 loses stealth.
		// Or if Red 3 really was Red 1.
		//
		// So the situation can be difficult to assess.  Human players
		// may be better able to assess the risk, so it is best
		// for the ai to avoid unknown exchanges.
		//
		// Probability based strictly on remaining piece ranks
		// and number is of little use because piece encounters
		// are rarely random.  A common example is:
		// R2
		// B3
		// B? B? B? B?
		// B? B? B? B?
		//
		// Red has the move.  Should it take B3?  Based on strict
		// random probability, it should, because there is only
		// one One and eight other unknowns in the field, so there
		// is only a small random chance that the unknown blue
		// adjacent to Blue Three is Blue One.  But because Blue
		// has left its Blue Three subject to attack, it is likely
		// that Blue Three IS protected by Blue One.
		//
		// So in an unknown exchange, the AI always loses
		// its actual piece value but gains the
		// the stealth value of the piece that attacks it
		// 
		// For example,
		// xxxxxxx
		// x R4 --
		// x B3 B?
		// Red Four and Blue Three are known.  Red Four will move
		// towards Unknown Blue because at least it will gain
		// the stealth value of Unknown Blue.
		//
		// But if Blue Three was also unknown, the choice is
		// a toss-up between unknowns.  If Red Four stays, B?(3)xR4
		// is a WIN and all Red gains is the stealth value of
		// a Three.  So Red Four will move towards
		// the other unknown because it has the chance to
		// gain a greater stealth value, because the unknown piece
		// could be a One or Two.
		//
		// Red Two should not take a Blue Three if it is protected
		// and Blue One is unknown.  Blue Three is worth 200 points.
		// So B?xR2 must be less than -200.
		// The stealth value of a One (60) plus 10 is 70,
		// so B?xR2 is 70 - 400,
		// or -330 so Red Two will not take Blue Three
		// if it is protected and Blue One is unknown.
		//
		// Another common example is a unknown piece chasing a Two.
		// B? R2 -- --
		// -- -- B? B?
		// B? B? B? B?
		//
		// Governed by the same rule, should Red Two
		// assume that the chaser is Blue One and flee past the
		// unknowns?   If Red Two stays, it loses 400 - 60 but
		// if moves, it loses 400 - 70.  This is a mere 10 points.
		// If Red can capture a piece elsewhere on the board,
		// it will and let its Two be attacked.
		//
		// However, because unknown Blue has chased Red Two,
		// Red now suspects that unknown Blue IS Blue One, making
		// Red Two invincible.
		// This could allow Red Two to flee past the other unknowns
		// with impunity.
		//
		// TBD: there needs to be some risk that a suspected
		// chase piece is not a One.
		//
		// Another borderline example is 4x5 when 5 is protected
		// by two unknowns, so the protectors do not have any
		// suspected rank.  This gains 50 points and the
		// stealth value of a Two (40).

		// When the AI piece is an unknown defender,
		// apparent piece value is the apparent win value.
		//

					assert tprank == Rank.UNKNOWN: "Known ranks are handled in WINS/LOSES/EVEN";

			if (foray[fprank.ordinal()]
				&& tp != lastMovedPiece		// maybe a responding defender
				&& forayMap[to]
				&& (fprank.ordinal() >= 5 || fp.hasMoved()))
				vm += unknownValue(fp, tp) - fpvalue/4;

			else if (fprank.ordinal() > lowestUnknownNotSuspectedRank) {

				vm += unknownValue(fp, tp) - fpvalue;

		// Prior to version 9.7, if the AI attacked an unknown
		// unmoved piece, the tpvalue was reduced
		// by the number of pieces remaining, because it was thought
		// that the likelyhood that the piece is a bomb increases
		// as the pieces become fewer.  This fixed the bug
		// where lowestUnknownExpendableRank is the Spy (the
		// last unknown piece on the board), so the value of
		// a moved Unknown would be the Spy.  This caused the AI
		// to slam into unknown pieces thinking that they were
		// the spy, but were just bombs.
		//
		// However, this violated the rule that UNK must return
		// a more favorable value than LOSES.  Thus if an AI
		// piece was forced between a sure loss and slamming into
		// an unknown piece, it chose the sure loss.
		//
		// Furthermore, it is not true that the risk of attacking
		// an unknown unmoved piece increases as the number of pieces
		// are removed from the board, because as the game progresses,
		// the AI identifies the suspected rank of bomb pieces, so
		// the risk actually remains constant.
		//
		// So the aforementioned bug must be fixed in a different
		// manner.  And instead, vm is debited a token amount so
		// that if the AI is forced into a choice between attacking
		// a moved unknown or an unmoved unknown, it will chose
		// the moved unknown.  (Note: the token amount must be
		// less than the difference between adjacent opponent stealth
		// values.  For example, R4xB?(3) is LOSES, but gains the
		// stealth value of a Three.  R4xB? is UNK, but gains the
		// stealth value of a Two.  This is a difference of only
		// five points.

				if (tp.moves == 0)
					vm = Math.min(-3, vm - 3);

			} else {

		// In an unknown attack, the AI loses its piece but gains
		// the stealth value of the opposing piece.
		// But if there are not any opposing pieces of lower rank,
		// the AI piece must be invincible, so the outcome
		// is UNK only if it attacks an unmoved piece
		// which could be a bomb (see winFight()).
		//
		// As defined, an invincible piece WINS against any moved
		// opponent piece.  If the opponent is unmoved, the
		// risk is that it completely loses by hitting a bomb,
		// gaining only in the discovery of a bomb.
		//
		// But by correctly guessing the setup locations of the
		// bombs and marking them suspected, the remaining unknowns
		// must be WINS.  So when an invincible piece attacks
		// an unknown unmoved piece, it gains the value of an unknown
		// and loses only 50% of its value.
		//
		// An important example is when an AI invincible piece gets
		// trapped in a sea of unmoved unknowns.  It is far
		// better rewarded to slam into the unmoved unknowns than
		// face (almost) certain capture by the chaser.

				assert !tp.hasMoved() : "AI " + fprank + " WINS or is EVEN " + " against " + lowestUnknownNotSuspectedRank + " (see winFight())";
				vm += Math.min(-3, values[1-fpcolor][unknownRank[1-fpcolor]] - fpvalue/2);
			}


		// What should happen to the ai piece
		// after an attack on an unknown piece?

		// In a field of unknowns, if an ai piece survives
		// and retains its value, it would never enter
		// the field, because each successive ply
		// would become more negative.  In the position below,
		// it would allow itself to be captured by Blue One
		// because attacking an unknown leads to another
		// position with more unknowns, and it would lose
		// twice its value.

		//       B1 
		// ?? ?? R4 ?? ??
		// ?? ?? ?? ?? ??

		// But often attacking one unknown is better
		// than attacking another, and this evaluation
		// depends on the ai piece survival.

		// In the position below, it is better for Red Four
		// to attack the unknown on the right because
		// the left unknown is a certain to recapture
		// by Blue One.  An addition bonus on the right
		// is that there is only one more possible unknown 
		// attack before freedom is assured.
		//    B1
		// ?? ?? R4 ?? ??
		// ?? ?? B2    ??

		// TBD: to solve this, check all adjacent squares
		// to the "to" square for known enemy pieces and
		// adjust vm appropriately.

		// If the AI piece does not survive, it is prone
		// to the following blunder.  In the position below,
		// Red Four may attack the unknown piece because
		// if Red Four does not survive, then the ai
		// cannot see the easy recapture.
		// R4
		// B?
		// B3

		// However, because the AI piece always loses its actual
		// value, the recapture is redundant.  vm is always
		// negative in this case.

		// Indeed, it is rare that vm is positive.  The only
		// case is a known AI Scout attack, because the
		// value of a known AI Scout is very low and may
		// be higher than the stealth of the unknown that it
		// attacks.

		// AI piece is removed.

				if (isPossibleBomb(tp)) {
					tp.setSuspectedRank(Rank.BOMB);
					setPiece(tp, to);
				} else {
					makeWinner(tp, fprank, true);
					if (tp.getRank() != fprank)	// could be even
						setPiece(tp, to);
				}

				} else {

		// AI IS DEFENDER (tp)

		// What is the likely outcome if a known AI piece
		// is defending an unknown attacker?
		//
		// If the attacker approaches the AI piece, it
		// acquires an ActingRankChase of the AI piece.
		// This causes winFight() to return WINS/LOSES.
		// (This case needs to be symmetric in
		// unknownValue() when the AI is the attacker.)
		//
		// Here is the example:
		//
		// -- R5
		// -- R3
		// B? --
		// -- B?
		//
		// Upper unknown Blue moves up to attack Red 3, acquiring
		// an ActingRankChase of Three.  Known Red
		// has a choice between Blue WINS by staying
		// put or take its chances with by approaching
		// the lower unknown Blue.  This is preferable result,
		// although one could argue the outcome could
		// be identical if Blue is bluffing. 
		//
		// But certainly, approaching the lower unknown
		// should not be worse. 
		// WINS/LOSES makes the unknown Blue attacker a Two
		// after the attack, and so WINS/LOSES returns a
		// not so negative result because of the stealth
		// value of a Two.  So unknownValue() must always
		// a more favorable result.
		//
		// To this end, unknownValue() returns the stealth
		// value of a piece two ranks lower, if one exists.
		// This still is not very much advantage (<20 points).
		// TBD: if there is only one unknown lower rank this
		// would be zero points, but this is not coded.

		// Note that an opponent piece cannot be protected by
		// a single unknown piece because the protector always gains
		// a chase rank and therefore has a suspected rank.

		// But if the piece is protected by two unknowns, neither
		// unknown gains a suspected rank.  So unknownValue() must
		// never return a result that would cause the AI to take
		// a piece protected by two unknowns.

		// TBD:  To correctly calculate the unknown value,
		// the AI would have to predict
		// all the ranks of the remaining unknown pieces.
		// All remaining pieces should not be considered equal.
		// Indeed, many of the remaining pieces are bombs and
		// the AI has little to fear from them.

		// So this is often where the AI errs.
		// xx R5 -- xx xx -- --
		// xx -- R4 xx xx -- --
		// -- B? -- R3 -- -- B?
		// -- -- -- -- -- B? --
		// -- -- B? B? B? B? B?
		// Left-most unknown Blue moves towards Red Three.
		// Red sees that even if it moves the Three to the right, an
		// unknown Blue piece can force it to be attacked.
		// Thus, Red allows its Three to be captured.
		// Even worse, Red may leave both its Three and Four unmoved,
		// because it assumes that unknown Blue will take
		// Red Three.

		// If Red Three becomes invincible when unknown
		// Blue approaches (Two is gone or known), then Red Three
		// can freely flee.

		// Here is a more complex example:
		// B4 R3 -- -- --
		// -- -- -- B? --
		// -- -- -- R2 --
		// -- -- B? -- B?
		//
		// It is Red's move.  Unknown Blue has just approached
		// Red Two, acquiring a chase rank of Two.  This makes its
		// suspected Rank a One.  So Red Two should move away
		// from that unknown and take its chances with the other
		// unknowns.  But it could also stay pat and instead
		// take Blue Four, assuming that the approaching unknown
		// is bluffing and perhaps one of the other two unknowns
		// is the actual One. What would you do?
		//
		// Note: If a piece approaches a known or unknown AI piece,
		// then it usually has a suspected rank, and is not handled
		// here.  But if a known or unknown AI piece approaches
		// an opponent unknown, then the opponent attack is
		// handled by the formula below because tp.moves != 0.
		// This is also heavily used when the AI evaluates
		// the risk of an unknown opponent piece approaching
		// its pieces.

						assert fprank == Rank.ONE || lowestUnknownNotSuspectedRank < fprank.ordinal() : "lower fp rank " + fprank + " WINS against " + lowestUnknownNotSuspectedRank + " (see winFight())";
						int tpvalue = apparentWinValue(fp, getChaseRank(fp, tprank, false), false, tp, actualValue(tp));

		if (fp != lastMovedPiece		// maybe a responding defender
			&& forayMap[to])
			tpvalue /= 4;

		// Outcome is the negation as if ai were the attacker.
		//
		// But note that the resulting value
		// of AI PIECE X OPP PIECE !=
		// OPP PIECE X AI PIECE,
		// because the former uses the
		// actual AI piece value and the
		// latter uses the apparent piece value.

		// TBD
		// if !fp.hasMoved()
		// adjust fpvalue based on the
		// probability that fp is a bomb
		// and cannot move

					fpvalue = unknownValue(tp, fp);

		// Note: prior to version 9.8, the number of
		// adjacent unknowns were counted and used to decrease
		// fpvalue to encourage the piece to pass the fewest
		// number of unknowns.  But actually, passing multiple
		// unknowns may establish flee ranks, and possibly creates
		// a forking opportunity, so passing multiple unknowns is
		// reasonable aggression.

					vm += tpvalue - fpvalue;
					vm = vm / distanceFactor(tp, fp);

		// vm is usually quite positive.  But in an endgame when
		// the opponent has no expendable pieces, it becomes
		// negative, encouraging AI pieces to approach opponent
		// pieces because the AI believes that the opponent
		// will not risk losing its remaining pieces in unknown
		// encounters.

		// But the AI still should not risk its valuable pieces
		// in this manner.  For example, the last AI Eight should
		// be discouraged from approaching an unknown opponent piece.


					vm = Math.max(vm, valueBluff(fp, tp));

		// If the AI appears to make an obviously bad move,
		// often it is because it did not guess correctly
		// what happened to the pieces after an unknown attack.
		// Any outcome is possible.
		//
		// However, if the AI piece has moved
		// or if the attacker has an chase rank of Unknown
		// (proving that it is hellbent on attacking),
		// assume worst case for AI: AI loses (or is even)
		// Otherwise the AI guesses that the defender
		// will remain and the attacker loses its piece.
		// This closely matches what the attacker is likely
		// thinking, because the unmoved piece could be a bomb.

					if (!isPossibleBomb(tp) || fp.getActingRankChase() == Rank.UNKNOWN) {
						makeWinner(fp, tprank, false);
						if (fp.getRank() != tprank) {
							fp.moves++;
							setPiece(fp, to); // won
						}
					} else {
						tp.makeKnown();
						setPiece(tp, to);
					}
				}
				break;
			} // switch

		assert !(fp.isKnown() && fp.getRank() == Rank.UNKNOWN
			|| tp.isKnown() && tp.getRank() == Rank.UNKNOWN)
			: "Exit known:" + fp.isKnown() + " " + fp.getRank() + "X known:" + tp.isKnown() + tp.getRank();

		} // else attack

		if (fpcolor == Settings.topColor)
			value += vm;
		else
			value -= vm;

	}

	// The AI always assumes that it loses in an unknown encounter.
	// It receives only the stealth value of one rank lower plus
	// a nominal value based on rank so that this function always
	// returns slightly more than LOSES.

	// Odds improve for higher ranked pieces
	// once *all* the opponents lower ranked
	// expendable pieces become known.  Then the AI knows
	// that it will gain more stealth value because
	// the remaining pieces must be lower ranked.

	public int unknownValue(Piece fp, Piece tp)
	{
		assert tp.getRank() == Rank.UNKNOWN : "target piece is known? (" + tp.getRank() + ")";
		assert lowestUnknownExpendableRank != 0 : "unknownValue: unknown rank should be known.";

		int tpvalue = 0;
		int fprank = fp.getRank().ordinal();

		// Once all the expendable ranks are gone,
		// the AI can expect to receive more stealth value
		// in an unknown exchange.

		int r = fprank-1;	// gains the stealth of one rank lower

		if (lowestUnknownExpendableRank < 5)
			tpvalue = stealthValue(tp.getColor(), 
				Math.min(r, lowestUnknownExpendableRank));

		// As the opponent uses up its expendable ranks,
		// it becomes more likely that the unknown piece
		// has higher stealth value.  If all the
		// opponent Fives are gone, then an AI Six is no
		// different from an AI Five, and hence should receive
		// the same stealth value as an AI Five.

		else if (fprank >= 5 && lowestUnknownExpendableRank >= r)
			tpvalue = stealthValue(tp.getColor(), 4);

		// unknownValue() should be more positive than LOSES.
		// For example, if an AI piece Six or higher
		// attacks a suspected Five, it LOSES but gains the
		// stealth of a Five.  If it attacks an unknown, 
		// the result is UNK and it gains the stealth value of a Five
		// plus a nominal amount.

		else if (fprank >= 6)

		// If a high ranked AI piece (6-9) has a choice of
		// an exchange with a suspected Five and some other piece,			// it should prefer the other piece it it is:
		// (1) a fleeing unknown (or a suspected Three or lower)
		// (2) completely unknown (or a suspected Four or lower)
		// (3) a piece with an unknown chase rank
		//
		// A loss to a suspected Five gains the Five stealth
		// which is the same as (3), so (3) needs to return
		// a slightly higher value
		// (which happens below with the value based on rank)

			tpvalue = stealthValue(tp);
		else if (r == 0)
			tpvalue = stealthValue(tp.getColor(), 1);
		else 
			tpvalue = stealthValue(tp.getColor(), r);

		// Version 9.7 introduced blufferRisk, which causes
		// the stealth value of suspected pieces to be reduced
		// if the opponent does not bluff, because the AI does
		// not need to attack the suspected pieces to find out
		// their true rank.  So to keep the value of UNK
		// significantly greater
		// than LOSES, version 9.8 adds a value based on rank.
		//
		// Yet the expected gain from an unknown encounter
		// should the AI piece actually win is likely low.
		// So this value cannot be higher than 1/2 the value of
		// the AI piece; otherwise if an opponent piece is guarded
		// by an unknown, the AI will take the opponent piece.
		// This usually happens with 4x5, because a this is
		// 200-100, only 100 points.  So the stealth of a Three (48)
		// plus the value based on rank must be less than 100 points.
		//
		// Note: this value decreases with the value of AI pieces
		// because then then AI loses less in an unknown encounter

		if (r == 7)
			r = 9;
		tpvalue += values[fp.getColor()][r+1] / 6;

		return tpvalue;
	}

        protected void moveHistory(Piece fp, Piece tp, int m)
        {
                undoList.add(new UndoMove(fp, tp, m, boardHistory[bturn].hash, value));
		bturn = 1 - bturn;
		depth++;
	}

	public void undo()
	{
		UndoMove um = getLastMove();
		if (um != null) {
			value = um.value;
			Piece fp = um.getPiece();

			// remove piece at target to update hash
			clearPiece(um.getTo());

			// place original piece and restore hash
			fp.copy(um.fpcopy);
			setPiece(fp, um.getFrom());

			// place target piece and restore hash
			if (um.tp != null) {
				um.tp.copy(um.tpcopy);
				setPiece(um.tp, um.getTo());
			}
		}

		undoList.remove(undoList.size()-1);
		bturn = 1 - bturn;
		depth--;
	}

	public void pushNullMove()
	{
		bturn = 1 - bturn;
		depth++;
		undoList.add(null);
	}

	// If the prior move was to the target square,
	// then the opponent must consider whether the ai is bluffing.
	// This could occur if the opponent moves a piece next to an
	// unknown ai piece or if the ai moved its unknown piece next to an
	// opponent piece.
	//
	// However, this gets stale after the next move, because if the ai
	// does not attack, it probably means the ai is less strong,
	// and the opponent will know it.
	//
	// A move becomes stale when the player
	// makes any other move rather than using the bluffing
	// piece to attack.  For example,
	// xx R? R? xx
	// xx -- -- xx
	// -- -- B1 B?
	// -- R2 B3 --
	// Unknown Red moves towards Blue One.  This is a very
	// good move because Blue One is protecting Blue Three
	// and must either move (losing the Three) or stand pat,
	// risking loss of Blue One if unknown Red is the Spy.
	// R?xB1 (LOSES) is very positive for Red in the move tree,
	// and must remain as the main line in the tree
	// even if Blue calls the bluff later in the tree
	// by moving some other piece.
	//
	// Because R?xB1 would not be played in practice,
	// if Blue actually does stand pat by moving some other
	// piece, Red will have to choose some other move than
	// R?xB1.  If it moves some other piece in the tree, bluffing will
	// become stale because the bluffing square no longer appears
	// as the prior move in the move tree.
	//
	// If Red does move some other piece, Unknown Red also
	// acquire an actingRankFlee which permanently marks it as a weaker
	// piece.

	public boolean isEffectiveBluff(Piece fp, Piece tp, int m)
	{
		// Bluffing doesn't work:
		//	- if the AI piece is known
		//	- if the opponent piece is expendable
		// 	- if the AI piece has fled from this same piece rank before
		//		(or neglected to attack)
		// 	- if the AI piece has fled from some other piece rank before
		//		(or neglected to attack)
		// 		and the value of that piece was greater than
		//		70% of the stealth value of the AI piece that
		//		could win the target piece of the current bluff.
		//		For example, if a Five passed by an AI piece,
		//		the AI piece can no longer bluff against a
		//		Five, Four, or Three, but could against a Two or One.
		//	- if the AI piece has chased a low-value
		//		(unknown or expendable) piece
		//	- if the AI piece is the flag or flag bomb
		//		and the opponent piece could be an eight
		// 	- if the unknown AI piece is near the opponent flag,
		//		because the opponent will call the bluff

		if (fp.getColor() == Settings.bottomColor
			|| fp.isKnown()
			|| tp.getRank().ordinal() >  4
			|| fp.getActingRankFleeLow() == tp.getRank()
			|| (fp.getActingRankFleeLow() != Rank.NIL
				&& fp.getActingRankFleeLow() != Rank.UNKNOWN
				&& tp.getRank() != Rank.ONE	// Spy flees from any other piece
				&& pieceValue(Settings.bottomColor, fp.getActingRankFleeLow()) > stealthValue(tp) * 7 / 10)
			|| (fp.getActingRankChase() != Rank.NIL
				&& isExpendable(Settings.bottomColor, fp.getActingRankChase().ordinal()))
			|| ((fp.getRank() == Rank.FLAG || fp.getRank() == Rank.BOMB)
				&& fp.aiValue() != 0
				&& (tp.getRank() == Rank.EIGHT || tp.getMaybeEight()))
			|| isNearOpponentFlag(Move.unpackTo(m)))
			return false;

		// (note that getLastMove(2) is called to get the prior
		// move, because the current move is already on the
		// stack when isEffectiveBluff() is called)

		UndoMove oppPrev = getLastMove(2);
		if (oppPrev == null)
			return false;

		// If this is an attack on the previously moved opponent
		// piece, return true.

		if (Move.unpackTo(m) == oppPrev.getTo())
			return true;

		UndoMove aiPrev = getLastMove(3);
		if (aiPrev == null)
			return false;

		// If this is the same piece the AI moved previously,
		// return true.

		if (Move.unpackFrom(m) == aiPrev.getTo())
		 	return true;

		// If the opponent chased any AI piece,
		// return true.  (Bluff remains effective while
		// opponent plays any delaying chase moves).

		UndoMove oppPrev2 = getLastMove(4);
		if (oppPrev2 == null)
			return false;

		if (Grid.isAdjacent(oppPrev2.getTo(), aiPrev.getFrom()))
			return true;

		return false;
	}

	// Unknown moved and unmoved AI pieces bluff.
	//
	// If the AI calculates that the bluff is effective
	// (see isEffectiveBluff()), the AI does not assign any
	// value to the loss of its piece in LOSES.  This makes
	// the AI able to bluff with any piece, no matter how valuable.
	//
	// An important case is an attack by the Spy, because 
	// an attack on a One by a protected Spy should be positive,
	// to support the following example:
	// -- R?
	// R? --
	// -- B1
	// Either unknown Red moves towards known Blue One.
	// Unknown Red *could* be the Spy.   If Red always
	// bluffed in this situation, then Blue could ignore
	// the attack.  But the AI wants Blue to respond to
	// a protected unknown by moving away, because this
	// can result in material gain.  For example,
	// xx -- R? xx
	// xx R? -- xx
	// -- -- B1 --
	// -- R2 B3 --
	// Unknown Red moves towards Blue One.  This is a very
	// good move because Blue One is protecting Blue Three
	// and must either move (losing the Three) or stand pat,
	// risking loss of Blue One if unknown Red is the Spy.
	// R?xB1 (LOSES) is very positive for Red in the move tree,
	//
	// This is why the AI does not currently assign value to the
	// the loss of its piece in LOSES during an effective bluff.
	// Any unknown piece would work.
	//
	// (TBD: But this causes the AI to make many high stakes and unnecessary
	// bluffs, so perhaps it should assign a small negative value,
	// based on the value of its piece, perhaps randomized).
	//
	// The AI must also risk its Spy occcasionally
	// if it wants to convince the
	// opponent that the unknown piece might be the Spy.
	// So B1xRS (WINS) followed by R?xB1 (LOSES) must at least sometimes
	// be positive.  WINS loses the Spy value.  So LOSES must
	// check the prior move.  If the prior move was a capture
	// to the target square, the captured piece value must be
	// negated.
	//
	// The AI doesn't have any clue *why* it is bluffing.  Its
	// only plan is to persuade the opponent into making a bad decision,
	// like moving into the arms of one of its lower ranked pieces
	// or attacking one of its unmoved pieces that could be
	// a bomb.  But this often backfires, if none of its pieces
	// are bombs, and the bluffing AI piece chases the opponent
	// piece through a field of its unmoved pieces, obliterating them.
	//
	// (TBD: can the search tree be used to increase the value of
	// a bluff if it leads to more possibilities of capture?
	// So a bluff that pushes the opponent piece towards a waiting
	// lower ranked piece or a bomb has more bluffing value?)
	//
	// The bluffing value must be less than the lowest value of
	// one of its pieces.  Otherwise the AI will sacrifice
	// bluffing pieces on the front line to repeat the bluff again.
	// For example:
	// xx R? R? xx
	// xx R? R? xx
	// B? -- B1 B?
	// B? B? B? B?
	// Red has the move.  R?xB1 loses its piece.  But if the
	// bluffing value for the approach of a red Unknown further back is 
	// is greater than the piece value, it will play R?xB1 just
	// to be able to move its other unknown piece towards Blue One.
	//
	// On the other hand, a low bluffing value often results in
	// no bluff at all if the opponent has a chase move at its
	// disposal.  For example:
	// -- -- -- -- -- R?
	// R4 -- xx xx B3 --
	// -- -- xx xx -- R?
	// B? -- -- -- -- --
	// The lower unknown Red is a Two.  Red should move the upper
	// unknown left to bluff an attack on Blue Three, which often
	// will move away (and then be captured by unknown Red Two.
	// But if Red only gets a small value for R?xB3, unknown
	// Blue can move upwards towards Red Four.  Then R?xB3 is dwarfed
	// by B?xR4.  So R4 must move away.  After that, the bluff is no
	// longer effective, so B3xR? wins the bluffing piece.
	//
	// To counteract, if the opponent move is a chase move,
	// the bluff must retain its effectiveness.
	// 
	// How it works.
	// Example:
	// R? R5
	// -- B3
	// Blue Three takes Red Five which is protected by
	// unknown Red.  Unknown Red is a Six, so 6x3 is a loss.
	// prev1 is the current move, R6xB3.
	// prev2 is B3xR5.
	// prev1.value is the value of the board after B3xR5.
	// prev2.value is the value of the board before B3xR5.
	// prev1.value - prev2.value is the value(-) of B3xR5.
	// 
	// This works no matter how deep in the move tree.
	// For example,
	// R? -- R5
	// -- B3 --
	// Red Five moves towards unknown Red.  The board is
	// then evaluated as in the above example.

	protected int valueBluff(int m, Piece fp, Piece tp)
	{
		assert fp.getColor() == Settings.topColor : "valueBluff only for AI";

		// (note that getLastMove(2) is called to get the prior
		// move, because the current move is already on the
		// stack when isEffectiveBluff() is called)
		UndoMove prev2 = getLastMove(2);
		if (prev2 != null
			&& prev2.tp != null
			&& prev2.getPiece() == tp) {

			UndoMove prev1 = getLastMove(1);
			int v = -(prev1.value - prev2.value);

		// Because a One will win any attack on a lesser piece,
		// regardless of whether the lesser piece is known,
		// the bluffing counter-attack
		// restores the full value of the lost piece.  This
		// encourages an unknown AI piece to approach an
		// opponent One if the AI piece has protection.  Thus
		// the One has to consider if the approaching piece is
		// a Spy, or the protecting piece is a Spy, with dire
		// consequence if the One guesses incorrectly.
		// For example:
		// -- -- R?
		// -- -- -- R?
		// R? R2 B1 --
		// -- xx xx --
		// Red Two and Blue One are known and the other pieces
		// are unknown. If the piece above Blue One moves down,
		// what should Blue do?  Most bots will play B1xR2 because
		// Blue has no move that does not result in a possible
		// loss if the unknown Red piece is actually the Spy.
		// But this is *exactly* what Red is hoping for, because
		// the Spy has been planted in usual fashion next to
		// Red Two.  (Humans would likely call the bluff
		// and play B1xR?, assuming that the Two is protected
		// by the Spy, but this is of course a risk that could
		// lose the game).
		// 
			if (tp.getRank() == Rank.ONE
				&& tp.isKnown()
				&& !tp.isSuspectedRank()
				&& !fp.isKnown()
				&& !prev2.tp.isKnown())
				return v;

		// If the attacker is suspected, the AI is only guessing
		// that the attacker is stronger.  This is a situation
		// that should be avoided, but the AI awards only a small
		// stipend; otherwise if the situation was highly negative,
		// the AI could lose material in trying to rectify the
		// situation.

			if (tp.isSuspectedRank())
				return v - valueBluff(tp, fp);

		// In other cases the AI only receives 2/3 of the
		// value of the bluffing piece.  This discourages the
		// AI from unnecessarily risking its pieces (known or
		// unknown fleers), encouraging it to find authentic protectors
		// for them.  But if the AI piece is truly unknown, the
		// the AI piece did not lose its apparent piece value
		// (see WINS), so prev1.value - prev2.value is small
		// (just the value from valueBluff() below).

		// One can argue that the bluffing value for known pieces should
		// be much higher.  In the example below, Unknown Blue
		// is about to fork two known Red Fives.  Red anticipates the
		// possible fork, but sees that the loss is inevitable.
		// (After Red Five moves up, Blue Four moves up, Red Five moves
		// up, Blue can take either Five, because the Five is only
		// protected by an unknown Four.  Because the bluffing value
		// is low, the AI sees that it will lose the Five anyway.
		// R? -- R4 R?
		// xx R5 -- xx
		// xx -- R5 xx
		// -- B? -- B?
		//
		// Another example suggesting a higher bluffing value:
		// B? B? B? B?
		// B? R4 -- B?
		// xx B3 -- xx
		// xx R7 -- xx
		// B? B? -- --
		// Blue Three has forked Red Four and Red Seven.
		// Red has the move.  Will Blue Three
		// actually take Red Four?  Possibly not, but because bluffing
		// value is low, Red Four moves sideways allowing Blue Three to
		// take Red Seven.
		//
		// Because of this uncertainly, the AI will sacrifice material
		// to avoid the possibility of capture of a known piece
		// of higher value, even when the piece appears to be
		// protected.
		//
		// TBD: the AI assumes that the opponent piece will call the
		// bluff, and hence may allow material to be captured
		// without playing the moves to protect the material.
		// That is, it will may just leave the material hanging.
		// This should be solved by "depth value reduction", but
		// this needs to be verified.

			return v * 2 / 3;

		} // prev2.tp != null

		return 0;
	}

	protected int valueBluff(Piece oppPiece, Piece aiPiece)
	{
		assert aiPiece.getColor() == Settings.topColor : "valueBluff only for AI";

		// Return the bluffing value for the approach of an
		// unknown AI piece towards a lower ranked opponent piece.
		// For reasons described above, this value must be low
		// (lower than the value of the lowest piece).
		// It should be greater than zero because a bluff can
		// be useful as a deterrent.
		// For example,
		// -- R? R? R? R?
		// B2 -- R6 -- R?
		// -- -- xx xx --
		// If all the unknown Red pieces are high ranked pieces,
		// and bluffing value is zero, R?xB2 (LOSES) is zero, so
		// Blue Two will be undeterred from cornering and
		// winning Red Six.
		//
		// If the bluffing value is non-zero, R?xB2 (LOSES)
		// is a deterrent, and if Red Six moves left,
		// there are four bluffs that Red Two must consider.
		// (In addition, the attack value is diminishes with depth).
		//
		// TBD: In reality, it is very unlikely that Blue Two
		// would chase Red Six past one unknown, let alone four.
		// The issue is how to separate worthwhile bluffs from
		// bluffing just for the sake of bluffing.

		int valueBluff = values[Settings.topColor][unknownRank[Settings.topColor]]/2;

		// WINS and EVEN

		// An important bluff is entrapment of an opponent piece by an unknown AI low
		// ranked piece and an unknown expendable AI piece.
		// For example,
		// | R1 -- R7
		// | -- B2 --
		// |xxxxxxxxxx
		// Red One and Red Seven are unknown, and Blue Two is known.
		//
		// Red One may approach Blue Two, forcing Blue Two to either remain
		// or chose to move towards unknown Red Seven.  R7?xB2 is LOSES but
		// returns "valueBluff".  An approach by Red One is slightly negative
		// because the AI is discouraged from using its low ranked pieces to
		// directly chase, because that is how the opponent can discover
		// its ranks.  So the net value is slightly below "valueBluff".
		//
		// So usually a better move is to approach Blue Two with Red Seven.
		// B2xR7? (WINS) is "-valueBluff".  And if Blue plays some other move,
		// R7?xB2 (LOSES) is "valueBluff".  So the net value is "valueBluff",
		// and therefore the AI should normally use its expendable pieces
		// to push the opponent pieces towards low ranked pieces.
		//
		// TBD: Yet if Red One was known (or Blue Two had no other moves)
		// the approach by unknown Blue Seven
		// would simply lose a Seven, because Blue Two has no other
		// option but to attack Red Seven.
		//
		// TBD: And Blue Two was trapped by unknown two expendable pieces,
		// an approach by either expendable piece would also be counterproductive.

		// Bluffing with the Spy against the opponent One
		// is encouraged.  Because the One is invincible,
		// the AI does this only when it has an unknown protector.
		// So the opponent One can become confused about which
		// of the unknown AI pieces is actually the Spy.

		Rank rank = oppPiece.getRank();
		if (aiPiece.getRank() == Rank.SPY && rank == Rank.ONE)
			return -VALUE_BLUFF;

		// Bluffing using valuable pieces is discouraged
		// (unless some further goal is highly positive, such as a win
		// of a valuable piece, the flag, or the protection
		// of its flag.)

		if (!isExpendable(aiPiece))
			return pieceValue(aiPiece)/7;

		// A suspected Four (that chased a Five) could well be a Five.
		// If so, the piece might attack and the AI would
		// lose its piece.

		if (rank == Rank.FOUR
			&& !oppPiece.isKnown()
			&& oppPiece.isSuspectedRank()
			&& !oppPiece.isRankLess())
			return pieceValue(aiPiece)/2;

		return -valueBluff;
	}

	public int getValue()
	{
		return value;
	}

	public void setValue(int v)
	{
		value = v;
	}

	private int stealthValue(int c, int r)
	{
		assert r != Rank.UNKNOWN.ordinal() : "Rank.UNKNOWN stealth depends on acting rank chase and flee";
		return valueStealth[c][r-1];
	}

	private int stealthValue(int c, Rank rank)
	{
		return stealthValue(c, rank.ordinal());
	}

	private int stealthValue(Piece p)
	{
		if (p.isKnown())
			return 0;

		Rank rank = p.getRank();

		// Unknowns that chase unknowns have low stealth.
		// Unknowns that flee unknowns have high stealth.

		// If an unknown piece flees from an opponent unknown piece,
		// it is a good indication that the opponent piece
		// is either a high ranked piece or an unknown low ranked
		// piece trying to maintain stealth.  Either way, the
		// piece has more stealth than a middle rank piece.

		if (rank == Rank.UNKNOWN) {
			if (p.getActingRankChase() == Rank.UNKNOWN)
				return stealthValue(p.getColor(), 5);
			if (p.getActingRankFleeLow() == Rank.UNKNOWN
				&& !isPossibleBomb(p))
				return stealthValue(p.getColor(), 3);

			return stealthValue(p.getColor(), 4);
		}

		return stealthValue(p.getColor(), rank);
	}

	// If the opponent has an invincible win rank,
	// then it is impossible to defend the flag,
	// so do not try, because it just leads to successive piece loss
	private void makeFlagKnown(Piece pflag)
	{
		int c = pflag.getColor();
		if (c == Settings.bottomColor
			|| invincibleWinRank[1-c] <= invincibleWinRank[c]) {
			pflag.makeKnown();
			grid.clearMovablePiece(pflag);
		}
	}

	//
	// BOMB VALUE
	//
	// How much is a bomb worth that surrounds a flag?
	// Quite a bit, if the removal allows another piece to access the flag.
	// But therein lies the catch.  If the search tree can discover
	// the flag after a bomb is removed by the eight, then
	// the bomb itself is still actually worthless.
	// Still there must be an incentive to remove them
	// because the search tree depth is limited and eventually
	// it is hoped that an exposed flag can be attacked.
	//
	// So a flag bomb is worth more than an unknown eight.
	// The value of an eight increases with the number remaining.
	//
	// The ai is always willing to exchange an eight for a flag bomb.
	// With several eights remaining, it is not willing to take a bomb
	// if it loses a low ranked piece (1-4) with a higher value
	// trying to get at the bomb, unless the search tree exposes
	// the flag (which then garners a higher value).
	//
	// An ai flag bomb is worth considerably more than an opponent
	// flag bomb.  This is because the ai never really knows if an
	// opponent flag bomb really surrounds the flag.

	private int aiBombValue(int color)
	{
		// A worthwhile bomb of "color" is worth the value
		// of the opposite 8, its stealth value, and more.
		// This makes taking a worthwhile bomb always worth sacrificing
		// an eight if necessary.

		// Note that a known miner taking a worthless bomb,
		// the value is zero, but an unknown miner,
		// the value is -stealthvalue.  This deters an
		// unknown miner from taking a worthless bomb.
		// This is calculated in WINS.

		if (color == Settings.bottomColor)
			return pieceValue(1-color, 8) + 10;

		// The ai is willing to sacrifice a known Three in order to
		// protect its own flag bombs.  Thus an unknown Two
		// approaching the flag can easily win an AI Three.

		// Note: the reason why the AI flag is not the highest
		// value at the beginning of the game is because bluffing
		// then could easily expose the AI low ranked pieces,
		// and the opponent would also conclude the location
		// of the flag structure by the strong AI response,
		// leading to inevitable loss.  But if the AI responds
		// to an unknown piece with a Four, most opponents may
		// not realize that the AI is trying to protect its flag,
		// and the loss of a Four is not a game loser; indeed,
		// if it exposes a lower rank like a One or Two, it could
		// be a good move.

		// Note: the value does not vary with the value of Three,
		// which could be diminished as pieces are removed from
		// the board.  Thus, towards the end of the game as
		// lower pieces are removed, the AI flag and bombs
		// become the most valuable AI pieces, and the AI will
		// do anything to protect them.

		// TBD:  More suspected rank analysis is needed to
		// determine if approaching pieces are Eights.  Most
		// opponents will send expendable pieces or Eights
		// to areas with a suspected bomb structure, rather than
		// low ranked pieces.  The AI needs to examine
		// its known pieces, and track the movement of approaching
		// pieces.  If an approacher is headed towards a Bomb,
		// it is probably an Eight.

		return VALUE_THREE;

	}

	// FLAG VALUE.
	//
	// In a game-playing algorithm that uses a search tree
	// to discover the end result of the game (in this case, the flag),
	// piece values should be viewed as simply a gimmick
	// to move the game forward towards the desired end result.

	// If flag value is set ridiculously high, the AI
	// will leave pieces hanging if the search tree discovers that
	// it can be taken.  But the flag is always unknown
	// by definition and therefore is comparable to the value of
	// other unknown pieces, depending on the intuition of the
	// player at guessing the flag location from among the
	// remaining unknown pieces.

	// When the flag value is set higher than other unknown pieces,
	// the opponent may be able to discern the protective
	// behavior of the AI and accurately guess the location of the
	// flag, which would put the AI at a further disadvantage.

	// Only after all other unknown pieces have been discovered
	// and there is only one remaining unknown piece of a given color,
	// (which then must by default be the flag)
	// can the value of the flag be set to infinity.

	private void setFlagValue(Piece pflag)
	{
		int color = pflag.getColor();

	// Set the flag value higher than a worthwhile bomb
	// so that an unknown miner that removes a bomb
	// to get at the flag
	// will attack the flag rather than another bomb.

		int v = aiBombValue(color);

	// Flag value needs to be higher than the lowest value piece
	// so that this piece will also attack a possible flag.
	// An attack on a possible flag is a WINS, but because the target
	// is unmoved and suspected, the attacker loses its complete value.
	// So the only pieces that will attack a suspected flag are
	// Eights and those with lessor value.
	//
	// For example, if R2 and R6 are on the board, Blue Flag value is
	// slightly more than R6.  This allows R6 to attack the Blue Flag.
	// But if R1 and R8 are on the board,
	// Blue Flag value is more than the R8.
	// R8 will attack the Flag and not R1, because we do not want
	// R1 to attack the Flag, which would lose if it turned out to be
	// a Bomb.
	//
	// TBD: This can be improved by considering all the
	// pieces remaining on the board, and then deeming which pieces
	// are expendable for attacking a possible flag.
	//
		int min = 9999;
		for (int r = 1; r <= 10; r++)
			if (rankAtLarge(1-color, r) != 0)
				if (pieceValue(1-color, r) < min)
					min = pieceValue(1-color, r);

		v = Math.max(v, min + VALUE_NINE);

		values[color][Rank.FLAG.ordinal()] = v;
	}

	// risk of attack (0 is none,  10 is certain)
	int apparentRisk(Piece fp, Rank rank, boolean unknownScoutFarMove, Piece tp)
	{
		// Risk of an unknown scout attack decreases with
		// with the number of scouts remaining.
		//
		// An important case is an attack on the Spy, because
		// 9xS wins the Spy (value between a Three and Four).
		// Thus the AI will sacrifice a Four to save the Spy
		// if the risk is 66% or greater.  Even if there is
		// only one opponent Scout still remaining, there is still
		// sustantial risk to the Spy (and the undiscovered One).

		if (unknownScoutFarMove)
			return 5 + unknownRankAtLarge(fp.getColor(), Rank.NINE)/2;
		// High ranking pieces

		int r = rank.ordinal();

		if (isPossibleBomb(tp)) {

		// tp is not known and has not moved
		// so it could be a bomb which deters low ranked
		// pieces from attacking an unknown unmoved piece

			if (r <= 4)
				return 1; // 10% chance of attack

		}

		// if the attacker is invincible, attack is almost certain

		if (isInvincible(fp)) {
			if (fp.isKnown())
				return 9;
			else
				return 5;
		}

		// If an opponent piece approaches
		// and the AI piece does not attack
		// attack is almost certain.
		// TBD: unless the opponent piece is protected

		// This is true regardless of the opponent piece rank.
		// For example,
		// -- R1 -- R5
		// xx xx B4 --
		// xx xx -- --
		// Red One is unknown, Blue Four and Red Five are known.
		// Blue Four has the move and forks Red One and Red Five.
		// If Red Five moves, then Blue Four will attack
		// Red One, winning its substantial stealth value.
		//
		// Blue Four is deterred from forking only because
		// it does not know the rank of Red One.  Thus, R1xB4
		// in WINS is positive only because of bluffing
		// (Red One *could* be an unknown Red Three).
		// Otherwise, unknown Red One will not attack Blue Four
		// because of the loss of stealth (it does not want
		// to reveal itself for just a Four, because that makes
		// the opponent Two invincible allowing run amok).
		// 
		// (note that getLastMove(2) is called to get the prior
		// move, because the current move is already on the
		// stack

		// So if the AI piece wasn't the piece that was
		// moved, meaning it stayed put, then attack is
		// almost certain.

		if (!isPossibleBomb(tp)) {
			UndoMove prev = getLastMove(2);
			if (prev != null && prev.getPiece() != tp)
				return 9;
		}

		if (r <= 4)
			return r;

		if (r == 10 && rankAtLarge(1-fp.getColor(), Rank.ONE) != 0) {
			// suspected spy probably won't attack
			// unless it really isn't the spy
			return 1;
		}

		// An attack on a fleeing piece is almost certain.
		if (!tp.isKnown()
			&& tp.getActingRankFleeHigh() != Rank.NIL
			&& r <= tp.getActingRankFleeHigh().ordinal())
			return 9;	// 90% chance of attack

		// Known Fives are unpredictable.  Suspected Fives
		// even more so (because they could be a 6, 7 or 9).
		if (r == 5 && !fp.isSuspectedRank())
			return 7;	// 70% chance of attack

		// The unknown defender has moved or the attacker is
		// an unknown or a high ranking piece that may actually
		// intend on attacking unmoved pieces.
		// This makes it much more likely for an unknown defender
		// to be attacked, and so the risk is much higher.

		return 9;	// 90% chance of attack

	}

	// This program has a big unsolved problem with
	// the horizon effect, particularly when the valuable
	// and vulnerable Spy could be attacked.  The horizon
	// effect is addressed by extended search,
	// but if the attacker is far from the Spy, then
	// it is possible that the attacker barely reaches
	// the Spy and extended search is insufficient
	// in depth to to prevent the AI from leaving pieces hanging.
	//
	// Note: If the opponent piece is unknown,
	// it is handled in UNK, but because move
	// generation of unknown pieces is limited (pruned off)
	// to 1 space of separation in AI.java,
	// the horizon effect is limited as well.
	// Yet it is still important.
	//
	// Note that this rule still means that if an AI piece
	// approaches an opponent piece that is far away,
	// it is still a threat.  That is why opponent "moves"
	// (the distance the opponent piece travels during
	// tree evalution) is used, and not the distance between
	// the attacker and defender.
	//
	// TBD: As search depth is increased, the distance
	// factor should be reduced.  The distance factor
	// causes the AI not to react the more moves that
	// approaching attacker makes during tree evaluation;
	// the idea is that the further the attacker,
	// the more likely it has some other target in mind

	protected int distanceFactor(Piece aiPiece, Piece oppPiece)
	{
		// If both the AI and opponent Pieces have apparent
		// ranks (including suspected rank), then the AI believes
		// the outcome is inevitable.

		if (aiPiece.getApparentRank() != Rank.UNKNOWN
			&& oppPiece.getApparentRank() != Rank.UNKNOWN)
			return 1;

		// But if either piece is unknown, then the outcome
		// is less certain, because the AI piece might not be the target
		// of an attack by the opponent piece.

		int d = oppPiece.moves - oppPiece.movesOrig;

	// square the distance

		return Math.max(1, d*d);
	}

	protected int apparentWinValue(Piece fp, Rank fprank, boolean unknownScoutFarMove, Piece tp, int v)
	{
		assert fp.getColor() == Settings.bottomColor : "apparentWinValue only for opponent attacker";

		// if the target is known, attacker
		// sees the actual value of the piece

		if (tp.isKnown())
			return v;

		// tp is unknown

		// Opponent does not know the actual value of the piece.
		// So what is the chance that the opponent will actually
		// attack?  This risk times the actual value of the
		// piece is the win value.
		//
		// In the example below, AI Blue Spy and Blue Four
		// and unmoved and unknown.  Red Three is known.
		// If the actual value of the target piece were assigned
		// to move, then Blue Four would attack Red Three
		// because the ai would guess that Red Three is going to
		// move left and attack Blue Spy, which is more valuable
		// than Red Three.  By attacking Red Three, Blue creates
		// a square for the Spy to move to.
		// xxxxxxxxxx
		// x BS B4
		// x -- R3
		//
		// But Red Three does not *see* the actual value of Blue Spy.
		// But there is still some risk (very low) that Red Three
		// will attack its unknown unmoved Blue Spy.
		//
		// In the following example, AI Blue Spy is unknown,
		// Red Three and Blue Two are known.   Blue Two should not
		// approach Red Three because it will flee into Blue Spy.
		// R3 BS ?B
		// -- ?B
		// B2 
		//
		// TBD: how is this handled?
		//
		// These two examples further suggest that the assignment
		// value is somewhere between actual and apparent value.
		//
		// Now consider the following example.
		// -- R? R? R?
		// -- R? R3 R?
		// R? R2 B7
		// All pieces are unknown except for Blue Seven.
		// Red has the move.  Should Red play R3xB7 to
		// protect the stealth of the Two?  R3xB7 loses
		// the stealth of the Three (40) but gains the
		// value of the Seven, resulting in -15.
		// B7xR2 is not a certainty, so the value is
		// the stealth of Red Two (-80) * 70% + 25 = -31.
		// So clearly Red should play R3xB7.  However, the value
		// is close enough that a situation elsewhere on
		// the board could allow Red to play some other move
		// and allow B7xR2.  But then Red is calculating that
		// Blue may play B7xR3 instead of B7xR2, since
		// neither piece is known.
		//
		// The risk of attack on an unknown piece is largely
		// based on the rank of the attacker.  High ranked
		// pieces are much more likely to attack unknown pieces,
		// except perhaps if the opponent is losing, when
		// the opponent may make a last ditch effort to
		// bolster a lost position.
		//
		// But the true risk depends assessing the actual target
		// of the attacker.  Factors that could be useful in
		// determining the most likely target are:
		// 1. distance to the target
		// 2. direction of the attacker
		// 3. multiple targets
		//
		// It is tempting to use depth rather than distance.
		// But that fails because it entices the AI
		// to leave pieces hanging by allowing the attacker to first
		// take the free material and then continue the
		// former attack later (at a higher depth and thus
		// lower value).
		//
		// The other problem with using depth is that the
		// transposition table would have to index by depth, because
		// it would cause the same position to be evaluated
		// differently at different depths.

		int risk = apparentRisk(fp, fprank, unknownScoutFarMove, tp);
		v = v * risk / 10;
		return v;
	}

	// TBD: combine with pieceValue()

	public int actualValue(Piece p)
	{
		// Piece aiValue does not depend on being known
		// because the ai sets it when the piece value is obvious.

		int v = p.aiValue();

		Rank rank = p.getRank();
		int r = rank.ordinal();
		if (rank == Rank.UNKNOWN)
			r = unknownRank[p.getColor()];

		v += values[p.getColor()][r];

		if (!p.isKnown())
			v += stealthValue(p.getColor(), r);

		// Suspected ranks have much less value than known ranks
		// because of uncertainty.  The value of a suspected rank
		// is the value of an unknown or the rank value divided by
		// blufferRisk [2..5],  whichever is greater.
		// This represents the risk that a suspected rank is bluffing,
		// not moving optimally, or the AI simply guessed wrong.
		//
		// (Note: the value of an unknown is the minimum value
		// of the remaining unknowns.  Hence, if the Spy and One
		// are the last two pieces remaining, it is the value
		// of the Spy.)
		//
		// For example:
		// RS R1
		// -- R3
		// B? --
		//
		// If Unknown Blue has been chasing R3,
		// it might be a One or a Two.  If its suspected
		// Rank is One, Red three could move between
		// unknown RS and unknown B3.  But if Blue
		// is actually a Two, it loses the Three.
		// So if the suspected One value (800) is divided by 5,
		// it would see the loss of the Three (200)
		// and a gain of 160, deterring the Three
		// from moving.
		//
		// Another example:
		// R? R2 R?
		// -- B1 --
		// 
		// All pieces are unknown, except that Blue One
		// chased a Red 5, so its suspected rank is a Three.
		// R2xB?(3) is a WIN, so the value is the loss of
		// stealth of Two plus the suspected value of Three.
		// -80 + (25+40), so unknown R2xB?(3) is a loss.
		//
		// However, if Red Two were known, it would take Blue 1.
		// For this to happen,
		// Red Two would have to approach Blue 1, because if Blue 1
		// approached Red Two, then it would have a chase rank
		// of One.  If it does happen, it still is not a blunder,
		// because an opponent who uses an unknown One to chase
		// a Five isn't playing well anyway.
		//
		// Note that the value of a suspected rank must always
		// be higher than the minimum rank value.  If the piece
		// is a suspected (but not known) Flag, the AI wants
		// to risk its minimum valued ranks on attacking the Flag
		// (WIN) even if the flag has adjacent pieces that can
		// win the AI piece.
		//
		// Until version 9.6, a losing AI attack on a suspected
		// rank created a known rank.  This caused
		// the AI to make a piece known before attacking it with
		// the lower piece to gain the higher known value rather
		// than the reduced suspected value. This is a waste of
		// material, and occurred often when an Eight was going to
		// take a flag bomb, because by another piece slamming
		// into the flag bomb first, it became more valuable.
		//
		// Yet the value of an opponent piece that successfully
		// attacked an AI piece must be that value.
		// For example,
		// B? R5 R3
		// B?(4)xR5 gains the value of the Five, or 50 points
		// (minus the stealth value of a Four).  R3xB?(4)
		// must be worth 100 points, not the suspected value
		// of 20 points.
		//
		// The dilemma is solved by clearing IS_SUSPECTED only
		// if an AI piece is attacked by an opponent piece
		// but leaving it IS_SUSPECTED when an opponent piece
		// is attacked by an AI piece.
		//
		// Thus it is possible to have a known but suspected
		// opponent piece (but only in the search tree, not
		// on the board).  The known status just means that
		// the stealth value of the opponent piece has already
		// been collected, so if the AI attacks it again,
		// it does not collect it twice.
		//
		// Note: if all the movable pieces have been moved,
		// the rest must be bombs or the flag, so the
		// AI sets the rank of the unmoved pieces to Bomb,
		// and makes them known and suspected.  So suspected
		// Bombs and the Flag have the same value as known ranks.
		//
		// Note that a suspected Spy also is currently assigned
		// a fixed value (i.e. it does not increase in value
		// with aging).  This is because the AI is confident
		// in its assignment of suspected rank to the Spy,
		// because it is unlikely (although possible) that
		// the opponent would bluff with protecting its Two.
		// This makes the discovery of a suspected Spy immediate
		// fodder for chase and attack.
		//
		// TBD: the confidence in suspected rank increases as
		// expendable pieces are reduced.
		// And in general, suspected rank assignment needs to
		// be improved, and afterwards this code needs to
		// be revisited.

		if (p.isSuspectedRank()
			&& rank != Rank.SPY
			&& rank != Rank.BOMB
			&& rank != Rank.FLAG) {
			if (p.moves < SUSPECTED_RANK_AGING_DELAY)
				v /= blufferRisk;
			else if (p.moves < SUSPECTED_RANK_AGING_DELAY * blufferRisk)
				v = v * p.moves / (SUSPECTED_RANK_AGING_DELAY * blufferRisk);

			v = Math.max(pieceValue(p.getColor(), unknownRank[p.getColor()]), v);
		}

		return v;
	}

	public int winFight(Rank fprank, Rank tprank)
	{
		return winRank[fprank.ordinal()][tprank.ordinal()];
	}

	public int winFight(Piece fp, Piece tp)
	{
		Rank fprank = fp.getRank();
		Rank tprank = tp.getRank();

		// The AI assumes that any unknown piece
		// will take a known or flag bomb
		// because of flag safety.  All other attacks
		// on bombs are considered lost.
		//
		// If the unknown has a (suspected) non-eight rank,
		// winFight() would return LOSES.
		// But the opponent piece could be a bluffing eight,
		// so the threat needs to be taken seriously.
		//
		// maybeEight determines whether a known Unknown
		// can take a bomb.  For example,
		// RF RB B? -- -- R9
		// RB -- R7 -- -- --
		// -- -- -- -- -- --
		//
		// Red has the move. If R9xB? or R7xB?,
		// the result is a known Unknown.  But R9xB? does not
		// clear maybeEight, so the piece can still take the bomb.
		// R7xB? clears maybeEight, so the piece
		// cannot take the bomb.
		//
		// Another example:
		// RF RB
		// RB --
		// -- --
		// B? R8 R2
		// -- B3
		//
		// Red has the move.  Unknown Blue has a chase rank of
		// Eight because it approached Red 8.  Red has no choice
		// but to attack Unknown Blue or risk losing the game.
		//
		// The flag must be known OR the attacker does not
		// have a suspected rank to allow the attack on the
		// bomb to succeed.  The AI assumes that an attacker
		// can succeed in attacking the flag position even
		// if the opponent makes a lucky guess.

		// Yet the AI still needs to make an intelligent guess
		// whether the oncoming attacker is after the flag
		// or after a valuable piece.  So if the chase rank
		// has matured (that is, the chase rank was acquired
		// long ago), the AI believes the original chase rank.
		// So in the following example,
		// RF RB
		// RB --
		// -- RB 
		// -- R3
		// B? --
		// Unknown Blue has a chase rank of Two.  It moves forward
		// towards the flag, which also attacks Red Three.
		// Because the chase rank has matured, Red Three
		// moves away.
		//
		// Note: maybeEight is cleared for matured suspected ranks
		// in Board.java
		//
		// Note: Prior to version 10.1, this was qualified by
		//
		// && (flag[Settings.topColor].isKnown()
		//	|| fprank == Rank.UNKNOWN)) {
		//
		// But if the attacker becomes a known unknown during
		// the search, then the attacker lost by attacking
		// a bomb.  For example,
		// xxxxxxxx|
		// -- RB RF|
		// R9 -- RB|
		// -- B? R7|
		// The AI thinks that it is no danger because if
		// unknown Blue moves up, R9xB? creates a known
		// but suspected Five (i.e. known unknown) and
		// although maybeEight was still true, the AI thought the
		// attacker would lose if it attacked the bomb structure.
		//
		// So now the AI depends only on maybeEight.  This means
		// that unmatured suspected ranks can successfully win
		// an attack on an unknown AI bomb structure.

		if (tprank == Rank.BOMB
			&& tp.getColor() == Settings.topColor
			&& fp.getMaybeEight()) {
			if (tp.isKnown() || tp.aiValue() != 0)
				return Rank.WINS;	// maybe not
			return Rank.LOSES;	// most likely

		} else if (fprank == Rank.UNKNOWN) {

			// AI IS DEFENDER (tp)

			assert !fp.isKnown() : "opponent piece must be unknown (" + fprank + "X" + tprank + ")";

		// By definition, attack on invincible rank loses or is even.
		// But invincible eights should be used for attacking
		// bombs rather than other eights.

			if (isInvincible(tp)) {
				if (isPossibleUnknownSpyXOne(tprank, fp))
					return Rank.UNK;

				if (tprank.ordinal() >= 8)
					return Rank.EVEN;	// could be LOSES
				else
					return Rank.LOSES; // could be EVEN

			} else if (isFleeing(tp, fp))
				return Rank.LOSES;	// maybe not

		// If the opponent no longer has any unknown expendable
		// pieces nor a dangerous unknown rank,
		// then it is unlikely that an unknown opponent
		// piece will randomly attack an unknown AI piece.
		// Thus, unknown opponent pieces can no longer serve
		// as blockers, preventing the AI piece from its goal.
		// This is a non-symmetric rule.
		// TBD: this might be coded to use hasFewExpendables,

			else if (lowestUnknownExpendableRank < 5
				&& tprank.ordinal() < dangerousUnknownRank
				&& !tp.isKnown())
				return Rank.LOSES;

		// In version 9.2, the AI could be up by a Five
		// at the beginning of the game,
		// then push its known Five along opponent
		// ranks without concern.  The inevitable result was
		// losing its known Five to a Four.
		// 
		// What about an unknown Five?  The opponent can
		// assume that the unknown piece is weak (5-9)
		// and possibly attack it with a Four, resulting in
		// an even greater loss, because an unknown Five is
		// worth even more than a known Five.
		//
		// But it still seems worthwhile to approach unmoved opponent
		// pieces with expendable pieces, as a way to gain
		// information about the opponent pieces.  If a piece
		// flees, then the AI can assume it is a target.
		// The risk is that it will lose the expendable piece
		// to a Four or Five, but this is a way to keep the
		// game moving.  So if the AI is winning, the AI encourages its
		// expendable pieces to approach unmoved unknowns,
		//
		// Note that simply passing an unmoved unknown that does
		// not attack results in a flee rank for the piece, so
		// if the piece subsequently moves, it becomes a target.
		// This is a non-symmetric rule.

		// Version 9.3: any unknown expendable piece is not
		// afraid of unmoved pieces.

		// This is a further step to keep the game going.

			boolean riskExpendable =
				(isExpendable(tp)
				&& !tp.isKnown()
				&& isWinning(Settings.topColor) >= VALUE_FIVE);

			if (riskExpendable && !fp.hasMoved())
				return Rank.LOSES;	// maybe not

		// What if the opponent rank has moved?  This
		// usually means that the opponent rank is weak, since
		// non-aggressive players move expendables to discover opponent
		// ranks before moving the lower ranks to attack them.

		// So in version 9.9, ranks with stealth values less
		// than a unknown piece (i.e. Fours and Fives) are allowed
		// to approach *moved* unknown pieces.  This dovetails
		// with chase() because unknown moved pieces do not deter
		// the chaser from its target.

		// This is a non-symmetric rule; the rank is not given
		// a WIN in attacking the unknown piece, because the
		// unknown piece could easily be a stronger piece.  The
		// idea is just to keep the opponent guessing.

			if (tprank.ordinal() <= 5
				&& !tp.isKnown()
				&& !isStealthy(tp)
				&& fp.hasMoved())
				return Rank.LOSES;	// maybe not

		// If the attacker has chased an unknown piece,
		// it usually indicates that the piece is weak (5-9)
		// However, be wary of this rule in the opponent flag area,
		// because unknown pieces are often approached
		// in the opponent flag area to fend off attack.
		// Unknown eights trying to get at the bombs are
		// especially vulnerable.

			else if ((fp.getActingRankChase() == Rank.UNKNOWN
					|| riskExpendable)
				&& !isNearOpponentFlag(fp)
				&& (dangerousUnknownRank == 99
					|| tprank.ordinal() >= 5)) {
					int rank = lowestUnknownExpendableRank;
					if (fp.isRankLess())
						rank--;
					if (tprank.ordinal() < lowestUnknownExpendableRank)
						return Rank.LOSES;	// maybe not
					else if (tprank.ordinal() == lowestUnknownExpendableRank)
						return Rank.EVEN;	// maybe not
			}

		// Any piece will take a SPY or FLAG

			else if (tprank == Rank.SPY
				|| tprank == Rank.FLAG)
				return Rank.WINS;

			return Rank.UNK;

		} else if (tprank == Rank.UNKNOWN) {

		// AI IS ATTACKER (fp)

			assert !tp.isKnown() : "opponent piece must be unknown (" + fprank + "X" + tprank + ")";

			if (fprank == Rank.BOMB || fprank == Rank.FLAG)
				return Rank.LOSES;	// but has bluffing value

		// If tp could be a bomb and fp is not an Eight,
		// the result is handled in UNK.  This must have
		// a higher value than LOSES.

			else if (fprank != Rank.EIGHT && isPossibleBomb(tp))
				return Rank.UNK;

		// By definition, invincible rank wins or is even
		// on attack of unknown moved pieces.
		// But invincible eights should be used for attacking
		// bombs rather than other eights.
			if (isInvincible(fp)) {
				if (fprank.ordinal() >= 8)
					return Rank.EVEN;	// maybe not, could be WINS
				else
					return Rank.WINS;	// maybe not, could be EVEN
			}

			else if (isFleeing(fp, tp))
				return Rank.WINS; // maybe not, but who cares?

			else if (tp.getActingRankChase() == Rank.UNKNOWN
				&& !isNearOpponentFlag(tp)
				&& (dangerousUnknownRank == 99
					|| fprank.ordinal() >= 5)) {
				int rank = lowestUnknownExpendableRank;
				if (tp.isRankLess())
					rank--;
				if (fprank.ordinal() < lowestUnknownExpendableRank)
					return Rank.WINS;	// maybe not
				else if (fprank.ordinal() == lowestUnknownExpendableRank)
					return Rank.EVEN;	// maybe not
			}
		// A spy almost always loses when attacking an unknown
			else if (fprank == Rank.SPY)
				return Rank.LOSES;	// maybe not

			return Rank.UNK;
		} // ai attacker

		return winFight(fprank, tprank);
	}

	// Note: Acting Rank is an unreliable predictor
	// of actual rank.
	//
	// Often an unknown opponent piece (such as a 1-3)
	// will eschew taking a known ai piece (such as a 4-7)
	// to avoid becoming known.  So to determine whether the
	// piece fled because it is a low ranked piece avoiding
	// discovery or a high ranked piece avoiding loss,
	// check the stealth value of the lowest unknown rank
	// against the value of the rank that fleed.  Add 25%
	// as a margin of error.  If the stealth value is less
	// than this value, the unknown should have taken the piece 
	// instead of fleeing, so the AI decides that the unknown
	// fled because it is a higher ranked piece, and is
	// no threat to the AI.   However, if the stealth value
	// is greater than this value, the unknown could be the
	// lowest ranked piece and fled to avoid discovery, so
	// the AI assumes that the unknown is a threat.
	//
	// Note: topColor stealthValues are used, because these are
	// actual stealth. bottomColor stealthValues are suspected stealth.
	//
	// If the fp actingRankFlee is >= tp rank
	// it is probably only an even exchange and
	// likely worse, so the move is a loss.
	// However, it is only a loss for low actingRankFlee
	// or for high tp rank.
	protected boolean isFleeing(Piece fp, Piece tp)
	{
		assert fp.getColor() == Settings.topColor : "fp must be top color";
		// fleeRankLow is always lower than fleeRankHigh.
		// So checking fleeRankHigh usually determines the strength
		// of the piece.  But if fleeRankHigh is unknown,
		// and fleeRankLow is a numeric rank (i.e. the piece
		// fled from both an unknown and some other rank),
		// then check fleeRankLow.

		// The AI considers all unknown opponent pieces
		// to be expendable (5-9) in a completely unknown encounter.
		// This is because the opponent is unlikely to risk the
		// stealth of its low ranked pieces in allowing them to
		// enter into a completely unknown exchange.
		// Under this assumption, an AI piece of Five or lower
		// should be a win or even.
		//
		// However, if the opponent piece flees from the AI unknown,
		// it obtains a fleeRankHigh of Unknown.
		// This may mean that the piece is strong, and the
		// AI should allow one of its weaker pieces to discover
		// its true identity.

		int fprank = fp.getRank().ordinal();
		// if (fprank <= 4 && fprank > dangerousUnknownRank)
		//	return false;

		Rank fleeRank = tp.getActingRankFleeHigh();
		if (fleeRank == Rank.UNKNOWN
			|| (fprank <= 4
				&& stealthValue(Settings.topColor, lowestUnknownNotSuspectedRank) * 5 / 4 > values[Settings.bottomColor][fleeRank.ordinal()]))
			fleeRank = tp.getActingRankFleeLow();

		int fleerank = fleeRank.ordinal();
		if (fleerank == fprank)
			return true;

		if (fleeRank == Rank.UNKNOWN
			|| fleeRank == Rank.NIL
			|| (fprank <= 4
				&& stealthValue(Settings.topColor, lowestUnknownNotSuspectedRank) * 5 / 4 > values[Settings.bottomColor][fleeRank.ordinal()]))
			return false;

		if (fleerank >= fprank)
			return true;

		// If the opponent piece fled from an AI piece 5 and up,
		// the risk of loss of an AI piece of the same rank
		// or even one rank higher is zero.

		else if ((fleerank == fprank
				|| fleerank + 1 == fprank)
			&& fleerank >= 5)
			return true;

		return false;
	}

	// An expendable Eight is an Eight that is not needed to
	// remove flag bombs, because either there are excess Eights
	// or fewer structures that can contain bombs.  (However, if
	// the opponent still has many unknown bombs, an Eight could
	// still be decisive in a game with few pieces remaining.)
	//
	// An expendable Eight is usually worth less than a Seven.
	// The question is how much less.  It depends on the endgame.  If bombs
	// are still very important, expendable Eights may be worth
	// more than a Seven.  But usually an endgame is all about
	// lower ranks winning, so Sevens are worth more.
	//
	// If an Eight is worth 5 points less,
	// then an unknown Seven may not want to attack an Eight
	// and lose its stealth.  And a known Seven will not attack
	// a protected Eight. 
	//
	// An expendable Eight is probably always worth more
	// than a Nine (cannot think of an example
	// where a Nine could be worth more?)
	//
	// TBD: need examples
	// - loss of Seven v. Eight => loss of game
	// - few remaining pieces + Eight
	// - multiple square movement v. single square movement

	void setExpendableEights(int color)
	{
		values[color][Rank.EIGHT.ordinal()] = values[color][Rank.SEVEN.ordinal()] - 3;
		values[color][Rank.NINE.ordinal()] = values[color][Rank.EIGHT.ordinal()] - 5;
	}

	boolean isNineTarget(Piece p)
	{
		Rank rank = p.getRank();
		if (rank == Rank.FLAG
			|| rank == Rank.SPY)
			return true;

		if (p.isKnown())
			return false;

		if (foray[9]
			&& forayMap[p.getIndex()])
			return true;

		if (rank == Rank.NINE	// nines have high stealth value but are not targets
			|| stealthValue(p) < values[1-p.getColor()][9])
			return false;

		return true;
	}

	// If all bombs have been accounted for,
	// the rest must be pieces (or the flag).
	// In this case, the piece is susceptible to
	// attack by an invincible piece.
	boolean isPossibleBomb(Piece p)
	{
		if (p.getRank() == Rank.BOMB)
			return true;

		return (!p.isKnown()
			&& p.moves == 0
			&& unknownRankAtLarge(p.getColor(), Rank.BOMB) != 0);
	}

	// How to guess whether the marshal will win or lose
	// when attacked by an unknown piece.
	//
	// The most conservative approach is to assume that
	// any unknown piece could be a Spy, if the Spy is
	// still on the board.
	//
	// A modest aggressive approach is if the AI has already
	// guessed the location of the Spy, it will assume
	// that any other piece is not a Spy.
	//
	// An more aggressive approach is to assume that if
	// the unknown piece has a suspected rank other than Spy
	// that the piece is not the Spy.
	//
	// But if the suspected rank was awarded by protection
	// rather than actual chase, the suspected rank is even less
	// reliable, so if the piece isRankLess() is true, then
	// the AI does not rely on the suspected rank.
	// For example,
	// R3 --
	// B4 B?
	//
	// Unknown Red Three approached known Blue Four and it did not
	// move on its turn, so unknown Blue now has a chase
	// rank of Two.  isRankLess() is true, so this is a case
	// where unknown Blue could still be a Spy.
	//
	// Also, a suspected Bomb or Flag might actually be
	// a Spy.  So the One could be lost if a bomb pattern
	// or suspected Flag actually turned out to be a Spy.
	// (NOTE: The One will avoid moving next to a suspected
	// bomb because of bluffing value of Bomb X Piece in LOSES).
	// R1 --
	// -- --
	// BS --
	// BF BB
	//
	// If a piece gains an chase rank of Spy, the One will
	// also avoid it.  For example:
	// B1 -- R1
	// -- BS --
	//
	// Blue Spy moves between Blue One and Red One.  It does
	// this only because Blue is winning and it wants to
	// remove Red One from the board in an exchange, even if
	// it has to sacrifice its Spy (which is worthless anyway
	// with the Ones gone).  Red One must either take the
	// piece or move away.
	//
	// The AI usually opts for the mid aggressive approach because
	// the most aggressive approach can be learned
	// and then used to defeat the AI (i.e., if the player
	// learns to bluff with its Spy, it can cause the AI
	// to lose its One).
	//
	// Also, if the opponent has a dangerous known or unknown rank,
	// the AI is sure to lose additional material,
	// so the AI switches to the most aggressive approach.

	boolean isPossibleUnknownSpyXOne(Rank fprank, Piece tp)
	{
		assert !tp.isKnown() : "Opponent piece must be unknown";

		if (fprank != Rank.ONE)
			return false;

		if (tp.getRank() == Rank.SPY)
			return true;

	// If the AI has guessed the location of the Spy, the AI One is safe
	// from any unknown piece.

		if (!hasUnsuspectedSpy(Settings.bottomColor))
			return false;

	// If the opponent has dangerous ranks, then the AI is aggressive
	// and assumes that the AI One is safe from suspected ranks as well

		if ((dangerousKnownRank != 99 || dangerousUnknownRank != 99)
			&& tp.isSuspectedRank()
			&& tp.getRank() != Rank.SPY
			&& !tp.isRankLess())
			return false;

	// If the One is not yet known at the outset of an exchange,
	// then it is less likely that the opponent Spy
	// will happen to be in position to capture the One.
	// While risky, the AI assumes that the opponent Spy
	// is shadowing the opponent Two, so unless
	// the One attacks the Two, the unknown AI One is safe from
	// capture by an unknown piece.  (If the AI does lose its One
	// and the AI still has its Spy, the AI usually can still win
	// anyway).

		if (!knownAIOne
			&& rankAtLarge(Settings.bottomColor, Rank.TWO) != 0) {
			UndoMove um = getLastMove(2);
			if (um != null
				&& um.tp != null
				&& um.tp.getRank() == Rank.TWO)
				return true;
			return false;
		}

		return true;
	}

	// If an opponent piece has a suspected rank,
	// it is quite possible that the AI has guessed wrong
	// and made a wrong assumption about whether an attack
	// results in a win, loss or even exchange.
	//
	// The risk of loss increases the narrower the difference
	// between the ranks as well as the value of the AI piece at risk.
	int riskOfLoss(Piece fp, Piece tp)
	{
		assert fp.getColor() == Settings.topColor : "fp must be top color";
		Rank fprank = fp.getRank();
		Rank tprank = tp.getRank();

		if (fprank == Rank.BOMB)
			return 0;

		assert fprank == Rank.SPY || fprank.ordinal() <= tprank.ordinal()
			: fprank + " loses to " + tprank;

		if (tp.isKnown())
			return 0;

		if (isInvincible(fp)) {
			if (isPossibleUnknownSpyXOne(fprank, tp))
				return values[fp.getColor()][fprank.ordinal()]*7/10;
			return 0;
		}

		// The Spy risks total loss regardless of the opposing rank
		if (fprank == Rank.SPY)
			return pieceValue(fp);

		// The AI believes the opponent rank is weaker if it fled
		// from a higher rank, but only if it
		// was not trying to remain cloaked.
		if (isFleeing(fp, tp))
			return 0;

		// If the opponent has a dangerous unknown rank,
		// it may approach any AI piece, and therefore may
		// have a suspected or unknown rank.  This increases
		// the risk of loss.   But the opponent may also decide to
		// keep the dangerous unknown rank hidden, so the
		// AI needs to assume some risk until the dangerous
		// unknown rank is discovered.

		int minRisk = 0;
		if (fprank.ordinal() <= dangerousUnknownRank + 2
			&& fprank.ordinal() > dangerousUnknownRank)
			minRisk = pieceValue(fp)/3;

		if (!tp.isSuspectedRank())
			return minRisk;

		// The risk of loss depends on the piece value
		// and the difference between the ranks.

		// Example
		// 4x4 : -100
		// 3x4 : -25
		// 2x4 : -11
		// Thus, if the AI must capture the suspected Four
		// it will use the lowest known rank (Two).
		// However, if the AI ranks are unknown, stealth
		// dominates, making a capture with the Three
		// the most palatable.
		//
		// Example
		// 5x5 : -50
		// 4x5 : -12
		// 3x5 : -5

		// Note: This fails if the opponent uses low ranks to bluff.
		// For example, if the unknown opponent One chases a Five,
		// it would make the AI think that the opponent was a Four
		// and so might attack it with a Three.  Another case is
		// a One bluffing as a Spy, protecting a Two from
		// the AI One. Because of the substantial difference in rank
		// of a Spy and almost any other piece, there is
		// little risk of loss in the AI attacking the suspected Spy,
		// which actually turns out to be the One.
		//
		// These situations do occur in play, but infrequently.
		// Incorporating the intelligence to discern this type
		// of bluffing is beyond the scope of this program
		// (and perhaps artificial intelligence).

		// riskOfLoss is reduced by low bluffer risk
		// once the piece has had time to attack AI pieces
		// and refine its suspected rank.

		int diff;
		if (tp.moves >= 10)
			diff = blufferRisk;
		else
			diff = 4;

		// protection rank is less reliable than chase rank
		if (tp.isRankLess())
			diff++;

		diff = 5 - Math.min(diff, 4);
		diff += tprank.ordinal() - fprank.ordinal();
		diff = diff * diff;

		int risk = pieceValue(tp) / diff;

		// TBD: e.g. a suspected rank of Four
		// that actually chased a piece (rather than protecting)
		// rarely turns out to be a lower rank, because a suspected
		// rank of Four means that the piece chased a known Five,
		// which are unpredictable, and could easily have
		// counter-attacked and exposed the lower rank.
		// Yet 3?x5 is about even, so Threes do chase Fives.

		// A suspected rank of Five (it chased a Six) could
		// be a Four or a Five, but usually it is a Five and
		// so 5x5? should not be so negative.
		if (tprank == Rank.FIVE)
			risk /= 2;
				
		return Math.max(minRisk, risk);
	}

	int pieceValue(int c, int r)
	{
		return values[c][r] + stealthValue(c, r);
	}

	int pieceValue(int c, Rank rank)
	{
		return pieceValue(c, rank.ordinal());
	}

	int pieceValue(Piece p)
	{
		return p.aiValue() + values[p.getColor()][p.getRank().ordinal()] + stealthValue(p);
	}

	boolean isNearOpponentFlag(int to)
	{
		return flag[Settings.bottomColor] != null &&
			Grid.steps(to, flag[Settings.bottomColor].getIndex()) <= 3;
	}

	boolean isNearOpponentFlag(Piece p)
	{
		assert p.getColor() == Settings.bottomColor : "Opponent piece required";
		return isNearOpponentFlag(p.getIndex());
	}

	public boolean isFlagBombAtRisk(Piece p)
	{
		Piece flagp = flag[1-p.getColor()];
		return p.getMaybeEight()
			&& flagp != null
			&& Grid.steps(p.getIndex(), flagp.getIndex()) <= 4;
	}

	// If the opponent has an unsuspected Spy, it is dangerous for
	// for the One to approach unknown pieces.  But if the Spy is gone
	// or the AI thinks it knows where it is, then the AI goes on
	// the rampage with its One.

	boolean hasUnsuspectedSpy(int c)
	{
		return hasSpy(c)
			&& suspectedRankAtLarge(c, Rank.SPY) == 0;
	}

	boolean isThreat(Piece fp, Rank tprank)
	{
		assert fp.getColor() == Settings.bottomColor;

		if (!fp.isKnown()
			&& !fp.isSuspectedRank()) {
			if (isInvincible(Settings.topColor, tprank.ordinal())) {
				if (tprank == Rank.ONE)
					return isPossibleUnknownSpyXOne(tprank, fp);
				return false;
			}
			return true;
		}

		Rank fprank = fp.getRank();
		return fp.getRank().ordinal() < tprank.ordinal();
	}

	boolean isStealthy(Piece p, int r)
	{
		return (p != null
			&& !p.isKnown()
			&& stealthValue(p) > pieceValue(1-p.getColor(), r));
	}

	boolean isStealthy(Piece p)
	{
		return isStealthy(p, unknownRank[1-p.getColor()]);
	}

	// For an AI bluff to be effective, the defending opponent piece
	// must not be invincible.  If the AI has incorrectly guessed
	// the opponent piece to be non-invincible, and it turns out to be
	// invincible, the AI loses big, so it needs to be more conservative.
	// For example,
	// -- R9 -- --
	// xx -- B3 xx
	// xx R2 -- xx
	// -- -- B2 --
	// All pieces are unknown except for Blue Three.  Red has the move.
	// It moves its Red Two to the right.  Red still has its unknown One,
	// so it is bluffing that suspected Blue Two will move away, allowing
	// it to capture Blue Three.  But if Blue Two turns out to be Blue One,
	// then BAM! Red loses its Two.

	boolean maybeIsInvincible(Piece p)
	{
		if (isInvincible(p))
			return true;
		if (p.isKnown()
			|| !p.isSuspectedRank())
			return false;
		return isInvincible(p.getColor(), p.getRank().ordinal()-1);
	}
}

