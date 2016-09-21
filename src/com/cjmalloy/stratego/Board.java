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
import java.util.Comparator;
import java.util.HashMap;
import java.lang.Long;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;


//
// The board class contains all the factual information
// about the current and historical position
//
public class Board
{
        public ArrayList<UndoMove> undoList = new ArrayList<UndoMove>();
	public static final int RED  = 0;
	public static final int BLUE = 1;
	public static int bturn = RED;
	public static final Spot IN_TRAY = new Spot(-1, -1);

	// number of moves unknown piece must make before
	// AI begins to believe suspected rank is actual rank

	public static final int SUSPECTED_RANK_AGING_DELAY = 15;
	
	public Grid grid = new Grid();
	protected ArrayList<Piece> tray = new ArrayList<Piece>();
	protected ArrayList<Piece> red = new ArrayList<Piece>();
	protected ArrayList<Piece> blue = new ArrayList<Piece>();
	// setup contains the original locations of Pieces
	// as they are discovered.
	// this is used to guess flags and other bombs
	// TBD: try to guess the opponents entire setup by
	// correllating against all setups in the database
	protected Piece[] setup = new Piece[121];
	protected static int[] dir = { -11, -1,  1, 11 };
	protected static long[][][][][] boardHash = new long[2][32][2][15][121];

	protected class BoardHistory {
		public long hash;
		protected HashMap<Long, UndoMove>  hashmap = new HashMap<Long, UndoMove>();
		public void clear() { hashmap.clear(); hash = 0; }
		public void put(UndoMove um) { hashmap.put(hash, um); }
		public UndoMove get() { return hashmap.get(hash); }
		public void remove() { hashmap.remove(hash); }
	}
	protected static BoardHistory[] boardHistory = new BoardHistory[2];

        protected int[][] knownRank = new int[2][15];   // discovered ranks
        protected int[][] trayRank = new int[2][15];    // ranks in trays
	protected int[][] suspectedRank = new int[2][15];	// guessed ranks
	protected Rank[] chaseRank = new Rank[15];	// usual chase rank
	protected boolean[][] invincibleRank = new boolean[2][15];// rank that can attack unknowns
	protected int[] invincibleWinRank = new int[2];	// rank that always wins
	protected int[] piecesInTray = new int[2];
	protected int[] possibleUnknownMovablePieces = new int[2];
	protected Piece[] flag = new Piece[2];  // flags

	protected static final int expendableRank[] = { 6, 7, 9 };
	protected int guessedRankCorrect = 1;
	public int blufferRisk = 4;
	protected int[] maybe_count = new int[2];
	protected int[] open_count = new int[2];
	protected int[][] lowerRankCount = new int[2][10];
	protected int[][] lowerKnownOrSuspectedRankCount = new int[2][10];
	protected boolean[] isBombedFlag = new boolean[2];
	protected int unknownBombs[] = new int[2];

	// generate bomb patterns
	static int[][] bombPattern = new int[30][6];
	static {
	for (int y = 0; y <= 2; y++)
	for (int x = 0; x <= 9; x++) {
		int flag = Grid.getIndex(x,y);
		int bpi = 0;
		bombPattern[y*10+x][bpi++] = flag;
		for (int d : dir) {
			int bi = flag + d;
			if (!Grid.isValid(bi))
				continue;
			bombPattern[y*10+x][bpi++] = bi;
		}
		bombPattern[y*10+x][bpi] = 0;	// end of pattern
	}
	}

	static {
		Random rnd = new Random();

	// An identical position differs depending on whose turn it is.
	// For example,
	// R8 R9	-- B5
	// -- B5	R8 --
	// (A)		 (B)
	//
	// Red has the move and can reach position B
	// by R9xB5, B5 moves up, R8 moves down.  Blue now has the move.
	//
	// (B) can also be reached if Red plays R8 down, Blue plays B5xR9.
	// Red now has the move.
	//

		for ( int c = RED; c <= BLUE; c++)
		for ( int k = 0; k < 32; k++)
		for ( int m = 0; m < 2; m++)
		for ( int r = 0; r < 15; r++)
		for ( int i = 12; i <= 120; i++) {
			long n = rnd.nextLong();

		// It is really silly that java does not have unsigned
		// so we lose a bit of precision.  hash has to
		// be positive because we use it to index ttable.

			if (n < 0)
				boardHash[c][k][m][r][i] = -n;
			else
				boardHash[c][k][m][r][i] = n;
		}
	}
	
	public Board()
	{
		//create pieces
		red.add(new Piece(RED, Rank.FLAG));
		red.add(new Piece(RED, Rank.SPY));
		red.add(new Piece(RED, Rank.ONE));
		red.add(new Piece(RED, Rank.TWO));
		for (int j=0;j<2;j++)
			red.add(new Piece(RED, Rank.THREE));
		for (int j=0;j<3;j++)
			red.add(new Piece(RED, Rank.FOUR));
		for (int j=0;j<4;j++)
			red.add(new Piece(RED, Rank.FIVE));
		for (int j=0;j<4;j++)
			red.add(new Piece(RED, Rank.SIX));
		for (int j=0;j<4;j++)
			red.add(new Piece(RED, Rank.SEVEN));
		for (int j=0;j<5;j++)
			red.add(new Piece(RED, Rank.EIGHT));
		for (int j=0;j<8;j++)
			red.add(new Piece(RED, Rank.NINE));
		for (int j=0;j<6;j++)
			red.add(new Piece(RED, Rank.BOMB));

		//create pieces
		blue.add(new Piece(BLUE, Rank.FLAG));
		blue.add(new Piece(BLUE, Rank.SPY));
		blue.add(new Piece(BLUE, Rank.ONE));
		blue.add(new Piece(BLUE, Rank.TWO));
		for (int j=0;j<2;j++)
			blue.add(new Piece(BLUE, Rank.THREE));
		for (int j=0;j<3;j++)
			blue.add(new Piece(BLUE, Rank.FOUR));
		for (int j=0;j<4;j++)
			blue.add(new Piece(BLUE, Rank.FIVE));
		for (int j=0;j<4;j++)
			blue.add(new Piece(BLUE, Rank.SIX));
		for (int j=0;j<4;j++)
			blue.add(new Piece(BLUE, Rank.SEVEN));
		for (int j=0;j<5;j++)
			blue.add(new Piece(BLUE, Rank.EIGHT));
		for (int j=0;j<8;j++)
			blue.add(new Piece(BLUE, Rank.NINE));
		for (int j=0;j<6;j++)
			blue.add(new Piece(BLUE, Rank.BOMB));

		tray.addAll(red);
		tray.addAll(blue);

		Collections.sort(tray);
		boardHistory[0] = new BoardHistory();
		boardHistory[1] = new BoardHistory();
	}

	public Board(Board b)
	{
		grid = new Grid(b.grid);
				
		tray.addAll(b.tray);
		undoList.addAll(b.undoList);
		setup = b.setup.clone();
		blufferRisk = b.blufferRisk;
		guessedRankCorrect = b.guessedRankCorrect;
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
			if (p.getColor() == Settings.bottomColor)
			 	p.setRank(Rank.UNKNOWN);
			setPiece(p, s);
			tray.remove(p);
			setup[Grid.getIndex(s.getX(), s.getY())] = p;
			return true;
		}
		return false;
	}

	// TRUE if valid attack
	public boolean attack(Move m)
	{
		if (validAttack(m))
		{
			Piece fp = getPiece(m.getFrom());
			Piece tp = getPiece(m.getTo());
			moveHistory(fp, tp, m.getMove());

			clearPiece(m.getFrom());
			clearPiece(m.getTo());

			fp.setShown(true);
			revealRank(fp);
			revealRank(tp);
			fp.setMoved();
			if (!Settings.bNoShowDefender || fp.getRank() == Rank.NINE) {
				tp.setShown(true);
			}

			int result = fp.getRank().winFight(tp.getRank());

		// Attacker wins:

			if (result == 1) {
				moveToTray(tp);
				setPiece(fp, m.getTo());
			}

		// Tie:

			else if (result == -1) {
				moveToTray(fp);
				moveToTray(tp);

		// Defender wins:

			} else {
				moveToTray(fp);
				if (Settings.bNoMoveDefender ||
						tp.getRank() == Rank.BOMB)
					setPiece(tp, m.getTo());

		// Setting: Defender moves to attacker's square

				else
					setPiece(tp, m.getFrom());
			}
			genChaseRank(fp.getColor());
			genFleeRank(fp, tp);	// depends on suspected rank
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
			if (getPiece(i, j).getActualRank().equals(Rank.FLAG))
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
				if (!getPiece(i, j).getActualRank().equals(Rank.FLAG))
				if (!getPiece(i, j).getActualRank().equals(Rank.BOMB))
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

		for (int i = 12; i <=120; i++)
			setup[i] = null;

		bturn = 0;
		boardHistory[0].clear();
		boardHistory[1].clear();
	}
	
	public Piece getPiece(int x, int y)
	{
		return grid.getPiece(x, y);
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
	
	public void clearPiece(int i)
	{
		setPiece(null, i);
	}

	public void setPiece(Piece p, Spot s)
	{
		setPiece(p, Grid.getIndex(s.getX(),s.getY()));
	}

	public void setPiece(Piece p, int i)
	{
		Piece bp = getPiece(i);
		if (bp != null) {
			rehash(bp, i);
		}

		if (p != null) {
			p.setIndex(i);
			grid.setPiece(i, p);
			rehash(p, i);
		} else
			grid.clearPiece(i);
	}

	// Zobrist hashing.
	// Used to determine redundant board positions.
	//
	// Unknown pieces (ai or opponent) are not all identical.
	// Hence, if two unknown pieces swap positions, the board is different,
	// because the AI bases rank predictions based on setup
	// and the history of the piece interactions.
	// Presently the AI does not consolidate all its information
	// (i.e. actingranks) into a single predicted rank, so the
	// hash cannot be relied on for unknown ranks.
	//
	// Should "known" be included in the hash?  At first glance,
	// a known piece creates a different board position from
	// one with an unknown piece.  However, for a piece to become known,
	// (1) another piece must have attacked it and lost
	// (2) a nine moved more than one square.
	// If (1), the board position is different because of the missing
	// piece.  If (2) and an opponent nine moved more than the one
	// square, its apparent rank would also change.  The only case
	// worth distinguishing is an unknown AI Nine moving more than one
	// square.
/*
	public void rehash(long v)
	{
		if (v < 0)
			v = -v;
		hash ^= v;
	}
*/

	// A board position (and hash) reflects all that each player
	// knows.  The AI knows its piece ranks and has information
	// it keeps about the opponent ranks.  The opponent sees
	// only what is known and what information can be gathered.
	//
	// Note that the hash is unreliable for unknown pieces
	// (see above).

	static public long hashPiece(int turn, Piece p, int i)
	{
		if (turn == Settings.topColor) {
			return boardHash
				[p.getColor()]
				[p.getStateFlags()]
				[p.hasMoved() ? 1 : 0]
				[p.getRank().ordinal()-1]
				[i];
		} else {
			Rank rank = p.getRank();
			if (p.getColor() == Settings.topColor
				&& !p.isKnown())
				rank = Rank.UNKNOWN;
			return boardHash
				[p.getColor()]
				[p.getStateFlags()]
				[p.hasMoved() ? 1 : 0]
				[rank.ordinal()-1]
				[i];
		}
	}

	public void rehash(Piece p, int i)
	{
		boardHistory[Settings.topColor].hash ^= hashPiece(Settings.topColor, p, i);
		boardHistory[Settings.bottomColor].hash ^= hashPiece(Settings.bottomColor, p, i);
	}
	
	public UndoMove getLastMove()
	{
		return undoList.get(undoList.size()-1);
	}

	public UndoMove getLastMove(int i)
	{
		int size = undoList.size();
		if (size < i)
			return null;
		return undoList.get(size-i);
	}

        boolean isThreat(Piece fp, Piece tp)
        {
                if (!fp.isKnown()
                        && !fp.isSuspectedRank()) {
                        if (isInvincibleDefender(tp))
                                return false;
                        return true;
                }

                Rank fprank = fp.getApparentRank();
                return fp.getApparentRank().ordinal() < tp.getApparentRank().ordinal();
        }

	// return true if piece is threatened
	public boolean wasThreatened(Piece tp, int i)
	{
		for (int d : dir) {
			int j = i + d;
			if (!Grid.isValid(j))
				continue;
			Piece p = getPiece(j);
			if (p != null
				&& p.getColor() == 1 - tp.getColor()
				&& isThreat(p, tp))
				return true;
		}
		return false;
	}

	// return true if chaser piece could be protected
	public boolean isProtected(Piece chaserPiece, Piece chasedPiece)
	{
		int chaser = chaserPiece.getIndex();
		for (int d : dir) {
			int j = chaser + d;
			if (!Grid.isValid(j))
				continue;
			Piece p = getPiece(j);
			if (p != null
				&& p.getColor() == chaserPiece.getColor()
				&& isThreat(p, chasedPiece))
				return true;
		}
		return false;
	}

	protected Piece getLowestRankedOpponent(int color, int i, Piece delayPiece)
	{
		for (int d : dir) {
			int j = i + d;
			if (!Grid.isValid(j))
				continue;
			Piece op = getPiece(j);
			if (op == null
				|| op.getColor() == color)
				continue;
			Rank rank = op.getApparentRank();

			if (delayPiece == null
				|| rank.ordinal() < delayPiece.getApparentRank().ordinal())
				delayPiece = op;

		}
		return delayPiece;
	}

	// Acting rank is a historical property of the known
	// opponent piece ranks that a moved piece was adjacent to.
	//
	// Acting rank is calculated factually, but is of
	// questionable use in the heuristic because of bluffing.
	//
	// If a piece moves away from an unprotected
	// opponent attacker, it inherits a flee acting rank
	// of the lowest rank that it fled from.
	// High flee acting ranks are probably of
	// little use because unknown low ranks may flee
	// to prevent discovery.
	//
	// However, the lower the rank it flees from,
	// the more likely that the piece rank
	// really is equal to or greater than the flee acting rank,
	// because low ranks are irresistable captures.
	// 
	// Note: genFleeRank() calls isProtected(), which depends on
	// invincibleRank[] and suspected ranks.  Thus if an unknown flees
	// from a suspected piece or a piece protected by a suspected bomb,
	// it will acquire a flee rank.  Although the fleer could have
	// fled because it does not want to attack because the suspected piece
	// might in fact be incorrect, the opponent may
	// at least suspect that the fleer would flee again from a
	// piece of the same rank.
	//
	// When a piece forks two or more opponent pieces, setting the
	// flee rank is just a guess if subsequently one of the
	// opponent pieces attacks,
	// because the neighboring pieces *could* be as strong
	// as the opponent piece that attacked.   Statistically,
	// however, the neighboring pieces are less likely to attack,
	// so the AI assigns the flee rank to all of them.

	void genFleeRank(Piece fp, Piece tp)
	{
		// Flee acting rank is set on all adjacent unknown pieces.
		// Thus, if the adjacent piece neglects to attack
		// an adjacent opponent (and become known), it retains
		// its unknown status, but because it fled, it can no
		// longer bluff by chasing the same rank again.
		//
		// However, it is difficult to determine whether the
		// player played some other move that requires
		// immediate response and planned to move the fleeing piece
		// later. So in version 9.1, this code was entirely removed,
		// with the thought that was better to leave the flee rank at
		// NIL until the piece actually flees.
		//
		// But the removal of the code meant that if an opponent piece
		// passes by an unknown player piece, and the player
		// piece neglects to attack, the flee rank would not be set,
		// and so the player would still
		// think that the piece would still be useful for
		// chasing the opponent piece away.  But of course
		// the opponent would call the bluff, and attack the
		// moved piece, because now the opponent knows it is
		// a weaker piece.
		//
		// So the code was restored for version 9.5, adding
		// additional checks to guess if the last move played
		// should delay the setting of flee rank.  This is definitely
		// tricky, and undoubtably does not handle all cases,
		// but this code is essential to prevent an opponent from using
		// low ranked pieces to probe the player's ranks and
		// baiting them to move.

		// New code in Version 9.5.
		// Check for a possible delay before setting the flee rank.
		// - if the player chases an equal or lower ranked piece.
		// - if the player flees from a equal or lower attacker
		//
		// For example,
		// xx xx B3 -- xx 
		// -- R4 -- R? -- 
		// -- B? -- -- --
		// Unknown Blue approaches Red Four, so unknown Blue
		// now is a suspected Three.  Unknown Red moves up
		// and attacks Blue Three.  Blue Three moves away.  Do not set
		// a flee rank on unknown Blue, because a lower ranked
		// Blue piece was moved instead.
		//
		// Contrast with this example:
		// | R7 --
		// | -- B?
		// | B? --
		// |xxxxxxx
		// Red Seven moves down and forks two Unknowns.
		// Flee rank is set on both unknowns.  So if neither
		// attacks Red Seven, Red Seven is safe to attack either
		// one.

		// delayPiece is lowest of:
		// 1. the piece just captured
		// 2. a piece chased by the last move
		// 3. a piece fled from by the last move
		Piece delayPiece = getLowestRankedOpponent(fp.getColor(), getLastMove().getFrom(), tp);
		delayPiece = getLowestRankedOpponent(fp.getColor(), getLastMove().getTo(), delayPiece);

		for ( int i = 12; i <= 120; i++) {
			if (!Grid.isValid(i))
				continue;
			Piece fleeTp = getPiece(i);
			if (fleeTp == null
				|| fleeTp == fp
				|| fleeTp.isKnown())
				continue;
			
			for (int d : dir) {
				int j = i + d;
				if (!Grid.isValid(j))
					continue;
				Piece op = getPiece(j);

				if (op == null
					|| op.getColor() != 1 - fleeTp.getColor()
					|| isProtected(op, fleeTp))
					continue;

		// Handle this case:
		// | -- R? --
		// | B3 B? R5
		// | -- -- --
		// After R5xB? and B3xR5, unknown Red gains an immediate
		// flee rank so that Red will realize that it must move
		// away.

				Rank rank = op.getApparentRank();
				if (op == fp) {
					fleeTp.setActingRankFlee(rank);
					continue;
				}

				if (rank == Rank.BOMB
					|| (delayPiece != null
						&& rank.ordinal() >= delayPiece.getApparentRank().ordinal()))	// new in version 9.5
					continue;

				fleeTp.setActingRankFlee(rank);
			}
		}
	}

	// stores the state prior to the move
	// the hash is the position prior to the move
	protected void moveHistory(Piece fp, Piece tp, int m)
	{
		UndoMove um = new UndoMove(fp, tp, m, boardHistory[bturn].hash, 0);
		undoList.add(um);

		boardHistory[bturn].put(um);
		bturn = 1 - bturn;

	}

	// check if the piece was trapped
	boolean wasTrapped(Piece trapPiece, int j)
	{
		for (int d : dir) {
			int i = j + d;
			if (!Grid.isValid(i))
				continue;
			Piece p = getPiece(i);
// TBD: check rank
			if  (p != null
				&& p != trapPiece)
				continue;
			if (!isGuarded(trapPiece.getColor(), i))
				return false;
		}
		return true;
	}

	// check if the square is guarded
	boolean isGuarded(int color, int j)
	{
		for (int d : dir) {
			int i = j + d;
			if (!Grid.isValid(i))
				continue;
			Piece p = getPiece(i);
			if  (p == null
				|| p.getColor() == color)
				continue;
// TBD: check rank
			return true;
		}
		return false;
	}

	// Set direct chase rank

	// Set direct chase rank to the piece that just moved.
	// Do not set direct chase rank to any other piece.
	//
	// For example:
	// B? -- R3
	// Red Three approaches Unknown Blue.  Blue makes some
	// other move.  So after Blue makes some other move,
	// Unknown Blue should not acquire a chase rank because
	// Unknown Blue did not approach Red Three.
	//
	// Why would Blue make some other move?  It would appear
	// that Blue should take Red Three if Blue is lower ranked,
	// flee if Blue is higher ranked, or stay put, attack, or
	// flee if even ranked?
	//
	// But there are reasons why Blue may not move.
	// 1. Unknown Blue is cornered.
	//    (this case is handled above when open == 0)
	// 2. Unknown Blue will lose the piece anyway because fleeing
	//    would lead Red Three to fork a more valuable piece.
	// 3. Unknown Blue is currently forked with another piece.
	// 4. Blue is a human player and just missed the fact
	//    that its piece is under attack.
	// 5. It is protected.
	//
	// Case #3 occurs frequently.  For example,
	// B? --
	// -- R3
	// B4
	// If Red Three forks unknown Blue and Blue Four, Blue Four
	// flees, unknown Blue should not acquire a chase rank.
	//
	// TBD: An opponent piece approaches a player piece
	// that is protected by a low piece.
	// The opponent piece is also protected, so it does not gain
	// a chase rank.  But what if the opponent protector then moves?
	// In this case, the opponent piece should acquire a chase
	// rank, because it will likely attack the player
	// piece if it become unprotected.
	// To solve this, we need to add an actingRankApproach
	// and rely on this instead of last move.

	void setDirectChaseRank(Piece chaser, Piece chased, int i)
	{
		Move m = getLastMove();
		if (m.getPiece() != chased)
			return;

		// Do not set chase rank on a piece if all of the squares
		// that it could move to were guarded.
		// For example,
		// -- R3 --
		// xx B4 --
		// xx -- R4
		// -- -- --
		// Red Three approaches suspected Blue Four.  Blue Four
		// retreats to a square next to known Red Four.
		// Blue Four does not acquare a chase rank of Three,
		// but remains a Four.

		if (wasThreatened(chased, m.getFrom())
			&& wasTrapped(chased, m.getFrom()))
			return;

		// Prior to version 9.6, if a chase piece had a
		// protector, it did not acquire a chase rank, because
		// the strong piece could be either the chase piece or
		// or the protector:
		//	|| unknownProtector != null
		//	|| (knownProtector != null
		//		&& knownProtector.getRank().ordinal() < chaser.getRank().ordinal()))
		//
		// Then in TestingBoard, the chase piece acquired a
		// temporary suspected rank so that the AI would treat
		// the chase piece as a strong piece.
		//
		// In version 9.6, this code was removed, because
		// of this example:
		// B? B? R2
		// -- -- -- B?
		// Red Two has been approached by unknown Blue, and
		// so the chaser acquired a temporary suspected rank of One.
		// This lead Red Two to believe that it was invincible,
		// so moved down.  But on the next move, it was no
		// longer invincible, and was trapped!
		//
		// The problem could have been solved by only resetting
		// Piece.moves to prevent Red Two from becoming invincible.
		// This code change was made as well, but it is
		// thought that setting a permanent chase rank is better,
		// to encourage the AI to try to identify
		// the chase piece by attacking it with a piece of
		// expendable rank.  Then, if it is unable to do so
		// within the allotted number of moves, the chase rank
		// matures, and from then on the AI believes that the
		// chase piece was the actual chaser.
		//
		// In version 9.10, the code was restored,
		// with the idea that if a neighboring piece has exactly
		// the same chase rank (or a known rank of one less),
		// then no chase rank should be assigned.   For example,
		// R2 --
		// B? B1
		// Unknown Blue has just approached Red Two, but it is
		// protected by Blue One.  Unknown Blue should not
		// earn a chase rank of Two.

		for (int d : dir) {
			int j =  i + d;
			Piece p = getPiece(j);
			if (p == null
				|| p.getColor() != chased.getColor())
				continue;

			if (p.isKnown()) {
				if (p.getRank().ordinal() + 1 == chaser.getRank().ordinal())
					return;
			} else if (p.getActingRankChase() == chaser.getRank())
				return;
		}
		
		if (chased.isKnown())
			return;

		Rank arank = chased.getActingRankChase();
		Rank chaserRank = chaser.getApparentRank();

		// An unknown chase rank, once set, is never changed to
		// a rank lower than Six.  This prevents being duped
		// by random bluffing, where a high rank piece chases
		// all pieces, low ranks as well as unknowns.  The AI believes
		// that unknown pieces that chase AI unknowns are probably
		// high ranked pieces and can be attacked by a Five or less.
		//
		// So if an opponent piece chases an AI unknown and then
		// chases a Five, it retains the chase rank of unknown,
		// because a Five chase rank would indicate a suspected rank
		// of Four, but a Four is less inclined to chase unknown
		// pieces, so that would be unlikely.
		//
		// Prior to version 9.3, chasing an unknown always
		// set the chase rank to UNKNOWN.
		// The problem was that once the AI successfully
		// trapped a low suspected rank that was forced to move
		// pass AI unknowns, it lost its suspected rank
		// and acquired the UNKNOWN rank.
		// This led the AI to approach the now unknown opponent
		// piece and thus lose its piece.
		//
		// From version 9.3 to 10.0, if a piece has a chase rank
		// of 4 or lower, the low rank is retained if the piece
		// chases an unknown.  The side-effect is that the AI is duped
		// by an opponent piece that chases a low ranked AI piece
		// and then chases an unknown.  This bluff results in
		// the AI wasting a piece trying to discover the true rank
		// of the bluffing piece.  If the AI piece is not able
		// to attack the bluffing piece, it can also lead to the
		// AI miscalculation of other suspected ranks on the board,
		// leading to a loss elsewhere.
		//
		// The key change in 10.1 is that all chase ranks
		// other than Four or Five are retained if the piece approaches
		// an Unknown.  This is because chasing a known piece
		// of any rank always gives more information than chasing
		// an Unknown.  For example, if a piece chases a Six,
		// it is probably a Five.  Then if it chases an Unknown,
		// it still is probably a Five.
		//
		// Since Version 10.1, if a piece has a Four or Five chase rank,
		// (suspected rank of Four) and it then approaches an
		// Unknown, the chase rank changes to Six which
		// usually results in a suspected rank of Five.
		// Thus, if the piece is actually a trapped Four,
		// the AI could approach it with a Five and lose
		// (but it probably won't approach, because 5x5 is even).
		// But much more often the piece is a Five, so that
		// chasing an Unknown is a sure sign that the piece
		// is not a Four but a Five, so the chance of a small loss
		// (4x5) is a necessary evil.
		//
		// There is no way around this.  Bluffing makes assignment
		// of suspected ranks a challenge.

		if (arank == Rank.UNKNOWN
			&& chaserRank.ordinal() <= 5)
		 	return;

		if (chaserRank == Rank.UNKNOWN
			&& (arank == Rank.FIVE
				|| arank == Rank.FOUR)
			&& !isInvincible(chased))
			chased.setActingRankChaseEqual(Rank.SIX);

		else if (arank == Rank.NIL 
			|| arank.ordinal() > chaserRank.ordinal()

		// If an unknown piece has IS_LESS set (because it protected
		// a piece) and then chases an AI piece,  clear IS_LESS
		// (actual chases are better indicators of actual rank
		// than protectors because of bluffing when an opponent
		// piece is trapped.

			|| chased.isRankLess()) {

			chased.setActingRankChaseEqual(chaserRank);
		}
	}


	// Return true if the chase piece is obviously protected.
	//
	// Examples:
	// If unknown Blue moves towards Red 3 and Blue 8,
	// the piece is not protected.  Unknown Blue gains a chase
	// rank of Three.
	// R3 -- B?
	// -- B8 --
	//
	// If unknown Blue moves towards unknown Red and Blue 8,
	// the piece is not protected.  Unknown Blue gains a chase
	// rank of Unknown.
	// R? -- B?
	// -- B8 --
	//
	// If unknown Blue moves towards Red 3 and Blue 2,
	// the piece is protected.  No chase rank assigned.
	// R3 -- B?
	// -- B2 --
	//
	// If unknown Blue moves towards Red 3 and unknown Blue,
	// the piece could be protected, or it could be a lower
	// ranked piece.  This is a difficult case to evaluate.
	// R3 -- B?
	// -- B? --
	//
	// Below is the same case. Both the Blue Seven and Blue Spy
	// are unknown.  If one of the unknown Blue pieces moves
	// towards Red 1, was it the Spy or was it the Seven?  If
	// B7 moves towards Red 1, and if this code assigned a chase rank,
	// the chase rank would be One.  R1xB1 removes both pieces from
	// the board.  But because B7 is not B1, Blue Spy takes Red 1.
	// R1 -- BS
	// -- B7 --
	//
	// So this code considers the attacker to be protected.  This prevents
	// the piece from obtaining an erroneous chase rank.
	//
	// In the following example, all the pieces are unknown.  One of the
	// blue pieces moves towards Unknown Red.  It gains a chase
	// rank of Unknown.
	// R? -- B?
	// -- B? --
	//
	public void isProtectedChase(Piece chaser, Piece chased, int i)
	{
		assert getPiece(i) == chased : "chased not at i?";

		// In version 9.6, direct chase rank no longer
		// depends on protecting pieces

		setDirectChaseRank(chaser, chased, i);

		// If the opponent is a bluffer, then
		// the AI does not assign any indirect chase ranks.
		// Otherwise, a bluffer could use any piece to thwart
		// an AI attack.

		if (blufferRisk == 5)
			return;

		Rank chasedRank = chased.getApparentRank();
		Rank chaserRank = chaser.getApparentRank();

		if (chaserRank == Rank.UNKNOWN) {

		// Because ranks 5-9 are often hellbent on discovery,
		// an adjacent unknown piece next to the chased should
		// not be misinterpreted as a protector.
		// Example 1:
		// R? B6 -- B?
		//
		// Example 2: 
		// R? -- B6
		// -- B? --
		// Unknown Blue moves towards Blue Six (in Example 1) or
		// Blue Six moves towards unknown Red in Example 2.

		// If Unknown Blue is viewed as a protector, it would acquire
		// a chase rank of 4.  But Unknown Blue might not
		// care about protecting Blue 6, if Blue intends
		// to attack unknown Red anyway.

			if (chasedRank.ordinal() >= 5	// or UNKNOWN
				|| isInvincible(chased))
				return;

		// If the chased piece is a strong but not invincible piece,
		// then there can be more that can be determined in this case
		// because if the chased believes that the Unknown
		// is a low ranked piece, then the protector must even be lower.
		//
		// For example,
		// R? -- B?
		// -- B2 --
		// Blue Two moves between Unknown Red and Unknown Blue.
		// If Blue thinks that Unknown Red could be Red One
		// and Unknown Blue is Blue Spy, then Red could guess that
		// Unknown Blue *is* Blue Spy.  But if Blue does not think
		// that Unknown Red is Red One, this is simply an attacking
		// move and nothing can be determined about Unknown Blue.
		//
		// Yet, if Unknown Red is actually a lower rank,
		// and its cover has been blown, then it follows
		// that Unknown Blue is probably Blue Spy, or otherwise
		// Blue has greatly miscalculated.
		// 
		// A surer way to determine whether Blue believes Unknown Red
		// is a superior piece is if Blue neglects to attack
		// Unknown Red.  For example,
		// R? B2 -- B?
		// If Blue Two does not attack Unknown Red, and instead
		// moves unknown Blue towards Blue Two, then Blue is signaling
		// that it believes Unknown Red is Red One.
		//
		// So if the last move was not the chased, then it can
		// be assumed that the player believes the unknown chaser piece
		// is a superior piece, and so the AI assumes that its
		// protector is even more superior.
		//
		// Another example,
		// R? B3 -- B?
		// If Blue Three does not attack Unknown Red, and instead
		// moves unknown Blue towards Blue Two, then Blue is signaling
		// that it thinks unknown Red is either Red One, and therefore
		// unknown Blue is Blue Spy OR it thinks that unknown Red is
		// Red Two, and therefore unknown Blue is Blue One.
		// So Red does not know if unknown Blue is Blue Spy or Blue One,
		// but both pieces are worthy targets to discover its identity.
		// 
		// The AI makes the assumption that unknown Blue is probably
		// Blue One in this case, although obviously it could be wrong.
		//
		// In version 10.1, the AI always believes that whenever the
		// opponent leaves a valuable piece vulnerable to attack by an
		// unknown AI piece, but protected, then the protection must be
		// strong.

		// If the chased is a lower or equal rank to the chaser piece,
		// the chased needs no protection.
		// Set the direct chase rank (when ranks are equal
		// and the the chased is unknown and suspected)

		} else if (chasedRank.ordinal() <= chaserRank.ordinal()
			|| chasedRank == Rank.FLAG
			|| chasedRank == Rank.BOMB) {
			if (chaser.isKnown()) {

				return;
			}

		// chaser piece is an unknown piece, but it has a rank
		// (i.e. not UNKNOWN, because that was tested for above)
		// But the chased doesn't know the rank, so the chased
		// could be assuming that it is a lower ranked piece,
		// and perhaps relying on a lower rank protector.
		// So continue.

		}

		Piece knownProtector = null;
		Piece unknownProtector = null;
		int open = 0;

		// If a piece moved adjacent to the chased piece,
		// and the chased piece has more than one adjacent
		// unknown piece, it is more likely that the piece
		// that actively moved adjacent to the chase piece
		// is the protector.

		boolean activeProtector = false;
		UndoMove um1 = getLastMove(1);
		if (Grid.isAdjacent(um1.getTo(), i)) {
			open++;	// adjacent square was open
			Piece p = um1.getPiece();
			if (p.getApparentRank() == Rank.UNKNOWN
				&& p.getActingRankFleeLow() != chaser.getRank()) {
				unknownProtector = p;
				activeProtector = true;
			}
		}

		for (int d : dir) {
			int j = i + d;
			if (!Grid.isValid(j))
				continue;
			Piece p = getPiece(j);

		// If an adjacent square is open, assume that the chased piece
		// is not cornered.  This assumption is usually valid,
		// unless the move to the open square is prevented by
		// the Two Squares rule or guarded by another enemy piece.

			if (p == null) {
				UndoMove um3 = getLastMove(3);
				UndoMove um7 = getLastMove(7);

		// oversimplication of a Two Squares check

				if (um3 != null
					&& um7 != null
					&& um3.getMove() == um7.getMove())
					continue;

		// check if the open square was occupied by the
		// piece that just moved

				if (j == um1.getFrom())
					continue;

		// assume the move to the open square is a decent flee move

				if (!isGuarded(chased.getColor(), j))
					open++;
				continue;
			}

			if (p.getColor() == chaser.getColor())
				continue;

			if (p.getApparentRank() != Rank.UNKNOWN
				&& chaserRank != Rank.UNKNOWN
				&& (p.getApparentRank().ordinal() < chaserRank.ordinal()
						|| p.getApparentRank() == Rank.SPY && chaserRank == Rank.ONE))
					return;

			else if (p.getApparentRank() != Rank.UNKNOWN
				&& chaserRank == Rank.UNKNOWN) {
				assert chasedRank != Rank.UNKNOWN : "chasedRank must be ranked";
				if (knownProtector == null
					|| p.getApparentRank().ordinal() < knownProtector.getApparentRank().ordinal())
					knownProtector = p;

			} else if (p.isKnown())
				continue;

			// more than one unknown protector?

			else if (activeProtector)
				continue;

			else if (unknownProtector != null)
				return;

		// If the protector fled from the chaser,
		// it isn't a protector.  For example,
		// -- -- R4 --
		// xx B5 -- xx
		// xx -- B? xx
		// Red Four moves down and forks Blue Five and unknown Blue.
		// Unknown Blue moves left.  Unknown Blue is not a protector.

			else if (p.getActingRankFleeLow() != chaserRank)
				
				unknownProtector = p;
		} // dir

		if (unknownProtector != null) {

		// If the chased piece is cornered, nothing should be
		// determined about the protection.  This is where
		// the AI deviates from worst case analysis, because
		// it guesses that cornered pieces are not protected.
		//
		// Of course, the piece could have a protector as
		// well as being cornered, but there is a good chance
		// that the piece does not have a protector and
		// this is a chance that the AI must take if it wants
		// to win material.
		//
		// Even if the last move was a move to adjacent to
		// the chased piece and the piece is cornered, the
		// approaching protector is probably bluffing.  Or
		// at least the AI thinks so.
		//
		// One exception is if the unknown protector has the
		// same suspected rank as the attacker.   In this case the AI
		// believes that the protector may actually be stronger.

			if (open == 0
				&& (chaserRank == Rank.UNKNOWN
					|| unknownProtector.getApparentRank() != chaserRank))
				return;

		// If a chased piece was forked, the player had to choose
		// a piece to be left subject to attack.  Thus if the piece
		// happens to have an adjacent unknown piece which could
		// be construed as a protector, do not set the chase rank.
		// For example,
		// xx xx -- --
		// -- B4 R3 --
		// -- B? B5 --
		// -- -- -- --
		// Red Three has forked Blue Four and Blue Five.  Unknown
		// Blue is a suspected isolated bomb.  Blue Four moves
		// left, leaving Blue Five subject to attack.  Do not
		// set a chase rank on unknown Blue.
		//
		// Note that the following check fails if Blue did not
		// move one of the forked pieces.
		// For example,
		// xx xx -- --
		// -- B4 R3 --
		// -- B? B4 --
		// -- -- -- --
		// Blue knows it will lose a Four, so neither flees.
		// The AI assigns a suspected chase rank to unknown Blue,
		// which happens to be an isolated bomb.

		// Thus it can be hard to determine whether a protector
		// is involved or not.  A simple approximation is:
		// If the prior opponent move was any piece to a square that is
		// two away from the chaser, it may mean:
		//	- one of the adjacent forked pieces fled
		//	- a protector stepped in to protect one or both
		//		of the pieces

			UndoMove m = getLastMove();
			if (Grid.steps(m.getTo(), chaser.getIndex()) == 2
				&& !Grid.isAdjacent(m.getTo(), chased.getIndex()))
				return;

		// If both the chased piece and the protector are unknown,
		// and the chased piece approached the chaser
		// (recall that the chased piece is actually the chaser, so
		// an unknown piece is attacking a known piece.)
		// then it cannot be certain which piece is stronger.
		//
		// For example, if an unknown Blue approaches known Red Two,
		// it is not possible to tell which piece is stronger.
		// B? -- R2
		// -- B? --
		//
		// The approaching unknown will acquire a chase rank of Two,
		// and no chase rank is assigned to the protector.
		//
		// However, if the unknown chased piece neglects to attack,
		// it can be certain that the unknown protector
		// is the stronger piece.
		//
		// For example, known Red Five approaches unknown Blue
		// (because it has a chase rank of Unknown from chasing
		// unknown pieces, suggesting that it is a high ranked
		// piece).  The unknown blue protector moves to protect
		// the unknown blue under attack.
		// -- B? R5
		// B? -- -- 

			if (m.getPiece() == chased) {
				if (!chased.isKnown())
					return;

			} else {

		// Opponent moved some piece other than the chased,
		// and neglected to capture the chaser,
		// likely implying that the chased piece protection
		// is strong.  But the opponent could also
		// be attacking some other player piece
		// that requires more immediate attention.
		//
		// Prior to version 10.1, flee rank was checked:
		//		Rank fleeRank = chased.getActingRankFleeLow();
		//		if (fleeRank != chaserRank)
		//			return;
		//
		// But this was a poor substitute for several reasons.
		// 1. genFleeRank is called after genChaseRank.
		//	This delayed the setting of the protector rank.
		// 2. the piece could have fled from multiple pieces,
		//	such as a Three fled from a Two, then
		//	was attacked by an unknown, and then did
		// 	not flee, implying that the protector was a Spy.
		//	But then the protector rank was not set.
		// 3. flee rank could have been set prior, and then
		//	the opponent chased a stronger piece.
		//	And then the protector rank was set in error.

			Piece defender = getLowestRankedOpponent(m.getPiece().getColor(), m.getTo(), chased);
			if (defender != chased)
				return;
		}

		// If the chaser is high ranked (or unknown) and the chased
		// is unknown, the chased piece may have neglected to attack
		// the chaser to retain stealth, and the chaser did not
		// flee to protect other pieces.  For example,
		// -- R7 -- B6
		// -- B2 BS --
		// B? -- -- B?
		// All pieces except for Red Seven are unknown.
		// Red Seven has approached unknown Blue Two.
		// The neighboring Blue unknown is Blue Spy.  Rather than
		// flee with Blue Two, unknown Blue Six to the right of
		// Red Seven moves left.  Blue is hoping that Red will
		// either flee or attack unknown Blue Six to the right rather
		// attacking its Blue Two.  Had Blue Two fled, it would
		// signal to Red that the piece was a desirable target
		// and Red Seven would chase, thus forcing Blue Two to
		// attack to protect Blue Spy.  It would then be obvious
		// to Red than the neighboring piece was also a valuable
		// target.  (This is definitely advanced game play).
		//
		// Yet if the opponent moved a piece to protect,
		// a chase rank should be set.  For example,
		// -- -- |
		// -- R5 |
		// -- B? |
		// B? -- |
		// Red Five has approached an unknown.  The lower unknown
		// Blue moves up to protect the upper unknown, which
		// is probably a Seven or a Nine.  The lower unknown
		// should gain a chase rank of Five.

			if (m.getPiece() != unknownProtector
				&& chaser.getApparentRank().ordinal() >= 5
				&& !chased.isKnown())
				return;


		// If the chased piece is high ranked and just
		// captured an AI piece, it did so because it
		// wanted the AI piece regardless of protection.
		// For example,
		// xx xx -- -- |
		// B? B? R? R4 |
		// -- -- B? -- |
		//
		// Either Blue Unknown attacks unknown Red and wins.
		// Unknown Blue is a high ranked piece (5 to 8).
		// Unknown Blue may or may not be protected, because
		// its mission was to  attack unknown Red, regardless
		// of the consequence.

			if (chased.getApparentRank().ordinal() >= 5
				&& chased == m.getPiece()
				&& m.tp != null)
				return;

		// TBD: If the unknown protector is part of a potential
		// bomb structure, then the chased piece may just be
		// willing to sacrifice itself to protect the bomb structure.

		// strong unknown protector confirmed

			int r = chased.getApparentRank().ordinal();

		// One cannot be protected.
			if (r == 1)
				return;

			int attackerRank = 0;

			if (chaser.isKnown()) {
				attackerRank = chaser.getRank().ordinal();
				assert attackerRank < r : "known chaser " + attackerRank + "  must chase unknown or lower rank, not " + chased.getRank();
				r = attackerRank - 1;
			} else {

		// Guess that the attacker rank is one less than
		// the chased rank (or possibly less, if not unknown)

				attackerRank = r - 1;
				while (attackerRank > 0 && unknownRankAtLarge(chaser.getColor(), attackerRank) == 0)
					attackerRank--;

				if (attackerRank == 0)
					return;

		// Guess that the protector rank is one less than
		// the attacker rank

				r = attackerRank - 1;
			}

		// Note: Note that prior assignment of chase rank
		// consumes unassigned ranks, so when there
		// are no more unassigned unknown lower ranks, the AI
		// assumes that the protector is bluffing.  Otherwise,
		// the opponent could continually protect its pieces
		// through bluffing with unknown protectors.
		// So now the AI initially believes the opponent, but
		// after all plausible protectors have been consumed,
		// the AI knows that the opponent is a bluffing opponent
		// so the AI stops assigning chase rank in this manner.
		// This can lead to material gain when the AI has a
		// locally invincible piece based on a prior assignment
		// of chase rank, and then attacks opponent unknowns
		// protected by other unknowns.

			while (r > 0 && unknownNotSuspectedRankAtLarge(chased.getColor(), r) == 0)
				r--;

		// known piece is protector

			if (knownProtector != null
				&& (knownProtector.getRank().ordinal() <= r
					|| (knownProtector.getRank() == Rank.SPY && r == 0)))
				return;

		// Only a Spy can protect a piece attacked by a One.

			Rank arank = unknownProtector.getActingRankChase();
			Rank rank;
			if (r == 0) {
				if (attackerRank != 1)
					return;

		// If the protector already has a chase rank,
		// don't let it bluff as a Spy.  This is evidence
		// of a bluffing opponent.

				if (arank != Rank.NIL)
					return;

				rank = Rank.SPY;
			} else
				rank = Rank.toRank(r);

		// Once the AI has indirectly identified the opponent Spy
		// (by attacking an opponent Two that had a protector)
		// the AI sticks with the guess, even if the suspected
		// Spy later tries to bluff by protecting some other piece.
		// This is because the Two is so valuable,
		// that it is unlikely, although possible,
		// that the opponent would risk the Two in a high stakes
		// bluff, protected by some other piece.
		// 
			if (arank == Rank.SPY)
				rank = Rank.SPY;

			if (arank == Rank.NIL || arank.ordinal() > r)
				unknownProtector.setActingRankChaseLess(rank);

		} // unknown protector

	}

	// Chase acting rank is set implicitly if
	// a known piece under attack does not move
	// and it has only one possible unknown defender.
	//
	// (This is how the ai can guess the spy: if the
	// a known Two is attacked by an ai piece, and it
	// does not move or another piece moves to protect it)
	//
	// Note that chase rank is set after the player move
	// but flee rank is set before the player move.

	protected void genChaseRank(int turn)
	{
		// Indirect chase rank now depends on unassigned
		// suspected ranks because of bluffing.
		genSuspectedRank();

		for ( int i = 12; i <= 120; i++) {
			if (!Grid.isValid(i))
				continue;
			Piece chased = getPiece(i);

		// Start out by finding a chased piece of the opposite color
		// (This can even be the One, because if a chaser piece
		// approaches the One and has a protector, then we know
		// the protector is a Spy (or acting like a Spy).
	
			if (chased == null
				|| chased.getColor() == turn
				|| chased.getApparentRank() == Rank.FLAG
				|| chased.getApparentRank() == Rank.BOMB)
				continue;

		// Then find a chaser piece of the same color
		// adjacent to the chased piece (rank does not matter
		// at this point).

			for (int d : dir) {
				int j = i + d;
				if (!Grid.isValid(j))
					continue;
				Piece chaser = getPiece(j);
				if (chaser == null
					|| chaser.getColor() != turn
					|| !chaser.hasMoved())
					continue;

		// If an unprotected unknown chaser forks a known and unknown
		// piece, assume that the chaser is after the known piece.
		// -- R3 --
		// B? -- R?
		// This assigns the chaser a rank of Two.
		//
		// If the known piece is expendable (5-9) or a Bomb,
		// assume the chaser is after either the known piece
		// or the unknown, so set an unknown chase rank.
		// For example,
		// -- R5 --
		// B? -- R?
		// The chaser may be a Four or a Five, but it is more often
		// a Five.  This check is consistent with setDirectChaseRank()
		// which sets the chase rank to Unknown if a piece chases
		// a known Five, and then an unknown, or vice versa.

				Piece chased2 = null;
				for (int d2 : dir) {
					int k = j + d2;
					if (!Grid.isValid(k))
						continue;
					chased2 = getPiece(k);
					if (chased2 == null
						|| chased2.getColor() == turn
						|| chased2 == chased) {
						chased2 = null;
						continue;
					}

		// If the chased piece is unknown, but is forked with
		// a known low rank piece (1-4), do not set an unknown
		// chase rank.

					if (!chased.isKnown()
						&& chased2.isKnown()
						&& chased2.getRank().ordinal() <= 4)
						break;

		// Unknown chaser can be assigned a chase rank.

					chased2 = null;
				} // d2
				if (chased2 != null)
					continue;

		// Now this is the tricky part.
		// The roles of chaser and chased are swapped
		// in isProtectedChase(), becaused the chased piece
		// tries to determine if it can win the chaser piece
		// based on its protection.  This can result in actingRankChase
		// assignment to a piece that is protecting the actual
		// attacker rather than the attacker.
		// For example:
		// B3 R4 -- R2
		//
		// Unknown Red Two moves towards known Red Four.
		// Red is the chaser because it had the move.
		// The same result occurs in the example below, where
		// Red Four moves between Blue Three and unknown Red Two:
		// B3 -- R2
		// -- R4 --
		// 
		// The usual case is usually simpler, such when unknown Red
		// approaches Blue Three in the example below.  Then
		// unknown Red gets an actingRankChase because it has
		// no protection.
		// B3 -- R?
		// 
		// In the following example, Red Four is known but all
		// other pieces are unknown.  Unknown chase rank is set because
		// it is unclear whether the unknown Blue piece is chasing
		// the Four or just bluffing, trying to get past to attack
		// an unknown piece.
		// RB RS
		// R4 --
		// -- B?
		//
		// This also can happen when an Eight bluffs to get at a flag
		// bomb.  The example below is a common bluff because Blue knows
		// that Red Five has to attack its unknown Blue piece
		// (if Blue has any unknown Eights), even if Blue has
		// unknown lower ranks (e.g. Blue Four).
		// RB RF
		// -- RB
		// R5 --
		// -- B?
		//

				isProtectedChase(chased, chaser, j);
			} // d
		} // i
	}

	// ********* start suspected ranks

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

	// The usual Stratego attack strategy is one rank lower.

	Rank getChaseRank(int r)
	{
		Rank newRank = Rank.UNKNOWN;

		// If a piece approaches an unknown piece, the approacher is
		// usually a Five, Six, Seven or Nine.  No suspected rank
		// is set, and the evaluation function checks the chase rank
		// for Rank.UNKNOWN and lowestUnknownExpendableRank to determine
		// the result of an attack, which must be more positive
		// than if the lowest unknown expendable rank were assigned as
		// the suspected rank of the piece, because an unknown
		// *could* be a higher ranked piece.
		//
		// Yet in the very special case
		// when there are no expendable opponent ranks remaining
		// except for unknown Fives, the AI assumes that the approacher
		// *is* a Five.

		if (r == Rank.UNKNOWN.ordinal()) {
			for (int e: expendableRank)
				if (unknownRankAtLarge(Settings.bottomColor, e) != 0)
					return Rank.NIL;
			if (unknownRankAtLarge(Settings.bottomColor, 5) == 0)
				return Rank.NIL;

			r = 6; 	// chaser is probably a Five
		}

		// See if the unknown rank is still on the board.

		// Note: Sixes are usually chased by Fives, but because 4?x6
		// is also positive, the chaser could be a Four.
		// So if an AI Five encounters a suspected Five
		// that has a Six chase rank, the result is EVEN,
		// but the risk of loss is higher than if the chase
		// rank is Unknown.
		//
		// Note: Sevens are usually chased by Sixes, but 5?x7
		// is also positive.

		if (r <= 7) {
			for (int i = r; i > 0; i--)
				if (unknownRankAtLarge(Settings.bottomColor, i) != 0) {
					newRank = Rank.toRank(i);
					break;
				}

		// Lower unknown rank not found.  It is not likely that
		// the attacker will chase a known rank with the
		// same unknown rank, unless the attacker is winning
		// and wants to reduce the piece count.
		// It is more likely that the attacker is bluffing.

		} else {

		// Eights and Nines could be chased by
		// almost any expendable piece, so no suspected rank is set.
		//
		// This needs to be consistent with winFight.  For example,
		// R9 B? R6
		// All pieces are unknown and Red has the move.  Blue
		// has a chase rank of unknown.
		// R6xB? is LOSES, because winFight assumes that an
		// unknown that chases an unknown is a Five.  So R9xB?
		// must create a suspected rank of Five.  If it created
		// a higher suspected rank, then the AI would play R9xB?
		// just to create a Six so that it can attack with its Six.
		//
		// Note: if a chase rank of Nine results in an Eight,
		// this could cause the AI to randomly attack pieces
		// with Nines hoping they turn out to be Eights
		// that it can attack and win.  So the AI assumes
		// that the chaser is not an Eight, unless all other
		// unknown lower ranked pieces are gone.
		//
			return Rank.NIL;
		}

		return newRank;
	}

	protected Rank getChaseRank(Rank rank)
	{
		int r = rank.ordinal();
		if (r <= 7)
			return chaseRank[r-1];

		return chaseRank[r];
	}

	protected Rank getChaseRank(Rank rank, boolean rankLess)
	{
		if (!rankLess)
			return getChaseRank(rank);
		return chaseRank[rank.ordinal()];
	}

	// Until version 10.1, bluffing determination relied on a simple count
	// of moves before the AI believed the piece was the rank
	// indicated by its chasing behavior.  But this causes the
	// AI to lose material when suspected ranked opponent pieces
	// that have not matured are near the flag.  It also results
	// in a long delay before the AI takes decisive action based
	// on the ranks that it has discovered.
	//
	// Version 10.1 relies on a 3 move successive chase sequence
	// (either chasing or fleeing, it doesn't matter)
	// to accelerate the rank maturation in addition to
	// the move count.
	//
	// Version 10.3 makes the Spy immediately suspected, because
	// often it never moves and certainly doesn't chase.

	boolean maybeBluffing(Piece p)
	{
		if (p.moves >= SUSPECTED_RANK_AGING_DELAY
			|| p.getRank() == Rank.SPY)
			return false;

		for (int i = 1; i <= 5; i+=2) {
			Move oppm = getLastMove(i);
			Move aim = getLastMove(i+1);
			if (aim == null
				|| oppm.getPiece() != p)
				return true;
			if (!Grid.isAdjacent(oppm.getTo(), aim.getTo())
				&& !Grid.isAdjacent(oppm.getFrom(), aim.getTo()))
				return true;
		}
		p.moves = SUSPECTED_RANK_AGING_DELAY;
		return false;
	}

	protected void setSuspectedRank(Piece p, Rank rank)
	{
		if (rank == Rank.NIL || rank == Rank.UNKNOWN)
			return;

		if (p.getRank() == Rank.UNKNOWN || p.isSuspectedRank())
			p.setSuspectedRank(rank);

		if (!maybeBluffing(p))
			suspectedRank[p.getColor()][rank.ordinal()-1]++;
	}

	//
	// suspectedRank is based on ActingRankChase.
	// If the piece has chased another piece,
	// the ai guesses that the chaser is a lower rank
	// If there are no lower ranks, then
	// the chaser may be of the same rank (or perhaps higher, if
	// bluffing)
	//
	protected void genSuspectedRank()
	{
		int piecesNotBomb[] = new int[2];
		for (int c = RED; c <= BLUE; c++) {
                        piecesInTray[c] = 0;
                        piecesNotBomb[c] = 0;
			flag[c] = null;
			unknownBombs[c] = unknownRankAtLarge(c, Rank.BOMB);
                        for (int j=0;j<15;j++) {
                                trayRank[c][j] = 0;
                                knownRank[c][j] = 0;
				suspectedRank[c][j] = 0;
			}
		} // c

		// add in the tray pieces to trayRank
		for (int i=0;i<getTraySize();i++) {
			Piece p = getTrayPiece(i);
			int r = p.getRank().ordinal();
			trayRank[p.getColor()][r-1]++;
			piecesInTray[p.getColor()]++;
		}

		for ( int i = 12; i <= 120; i++) {
			if (!Grid.isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p == null)
				continue;

		// reset suspected ranks to unknown
		// because these are recalculated each time

			if (p.getColor() == Settings.bottomColor
				&& p.isSuspectedRank()) {
				p.setKnown(false);
				p.setRank(Rank.UNKNOWN);
			}

			if (p.isKnown())
				knownRank[p.getColor()][p.getRank().ordinal()-1]++;
			if ((p.hasMoved()
				|| p.isKnown()
				|| unknownBombs[p.getColor()] == 0)
				&& p.getRank() != Rank.BOMB)
				piecesNotBomb[p.getColor()]++;

			if (p.getRank() == Rank.FLAG
				&& p.getColor() == Settings.topColor)
				flag[p.getColor()] = p;
		}

		for (int r = 0; r < 15; r++)
			chaseRank[r] = getChaseRank(r);

		for (int i = 12; i <= 120; i++) {
			if (!Grid.isValid(i))
				continue;
			Piece p = getPiece(i);
			if (p == null
				|| p.getColor() != Settings.bottomColor
				|| (p.isKnown() && !p.isSuspectedRank()))
				continue;

		// If the opponent still has any unknown Eights,
		// assume that the suspected rank can also be an Eight,
		// due to the possibility of bluffing to get at the flag.
		// (The maybeEight status can be cleared during the search
		// tree if a Seven or lower ranked piece attacks the unknown)

			if (unknownRankAtLarge(Settings.bottomColor, Rank.EIGHT) == 0
				|| (p.isSuspectedRank()
					&& !maybeBluffing(p)))
				p.setMaybeEight(false);
			else
				p.setMaybeEight(true);

			Rank rank = p.getActingRankChase();
			if (rank == Rank.NIL)
				continue;

		// If the piece both chased and fled from the same rank,
		// it means that the piece is not dangerous to the
		// same rank, so creating a lower suspected rank
		// would be in error.  Perhaps it should acquire a
		// suspected rank of the same rank?  But because this is
		// unusual behavior, the piece stays Unknown.

			if (rank == p.getActingRankFleeLow()
				|| rank == p.getActingRankFleeHigh())
				continue;

		// The AI needs time to confirm whether a suspected
		// rank is bluffing.  The more the suspected rank moves
		// without being discovered, the more the AI believes it.

			if (rank == Rank.SPY) {
				if (hasSpy(p.getColor()))
					setSuspectedRank(p, Rank.SPY);

			} else
				setSuspectedRank(p, getChaseRank(rank, p.isRankLess()));

		} // for

		for (int c = RED; c <= BLUE; c++) {

		// If all movable pieces are moved or known
		// and there is only one unknown rank, then
		// the remaining unknown moved pieces must be that rank.
		int unknownRank = 0;
		for (int r = 1; r <= 10; r++)
			if (unknownRankAtLarge(c, r) != 0) {
				if (unknownRank != 0) {
					unknownRank = 0;
					break;
				}
				unknownRank = r;
			}

		// If all pieces have been accounted for,
		// the rest must be bombs (or the flag)

		possibleUnknownMovablePieces[c] = 40 - piecesInTray[c] - piecesNotBomb[c]- 1 - Rank.getRanks(Rank.BOMB) + trayRank[c][Rank.BOMB.ordinal()-1];
		if (possibleUnknownMovablePieces[c] == 0) {
			for (int i=12;i<=120;i++) {
				if (!Grid.isValid(i))
					continue;
				Piece p = getPiece(i);
				if (p == null)
					continue;
				if (p.getColor() != c)
					continue;
				if (p.isKnown())
					continue;
				if (p.hasMoved()) {
					if (unknownRank != 0) {
						p.setRank(Rank.toRank(unknownRank));
						p.makeKnown();
					}
				} else {

		// The remaining unmoved pieces must be bombs (or the flag)
		// Make the piece known to remove it from the piece lists
		// because it is no longer a possible attacker.
		// This makes Bombs known, but they could be a Flag.
		// So subsequent code needs to make this check:
		// - if a bomb is known and not suspected, it is a bomb.
		// - if a bomb is known and suspected, it could be either.
		// This happens in possibleFlag().
		//
		// Note: makeKnown() clears suspected rank, so it is
		// set again as needed.  But the Bomb rank needs to be set
		// before makeKnown() because makeKnown will then clear
		// the movable piece grid.

				p.makeKnown();
				grid.clearMovablePiece(p);
				Rank rank = p.getRank();

				if (c == Settings.topColor)
					assert (rank == Rank.FLAG || rank == Rank.BOMB) : "remaining ai pieces not bomb or flag?";
				else if (unknownBombs[c] != 0) {
		// Set setSuspectedRank (not rank)
		// because the piece is suspected to
		// be a bomb, but it could be the flag.
		// See winFight().
		//
		// The value of a suspected bomb is zero.
		// Piece X Bomb and Bomb X Piece is a LOSS,
		// Eight X Bomb is a WIN.
		//
					p.setSuspectedRank(Rank.BOMB);
				}

		// if all the bombs have been found, then the flag
		// is somewhere in the remaining unmoved pieces.

				} // not moved
			} // for

		} // all pieces accounted for
		else if (c == Settings.bottomColor)
			possibleBomb();


		int lowerRanks = 0;
		int lowerKnownOrSuspectedRanks = 0;
		for (int r = 1; r <= 10; r++) {

		// Another useful count is the number of opponent pieces with
		// lower rank.  If a rank has only 1 opponent piece
		// of lower rank remaining on the board,
		// then it safe for the rank to venture out,
		// because it takes two pieces of lower
		// rank to corner another piece.
		//
		// TBD: Even so, because AI's look ahead is limited,
		// it can be trapped in a dead-end or by the Two Squares
		// rule or forked with another one of its pieces.
		// This can be solved by increasing look-ahead.

			lowerRankCount[c][r-1] = lowerRanks;
			lowerRanks += rankAtLarge(c, r);

			lowerKnownOrSuspectedRankCount[c][r-1] = lowerKnownOrSuspectedRanks;
			lowerKnownOrSuspectedRanks += suspectedRankAtLarge(c, r) + knownRankAtLarge(c, r);
		}

		} // c

		// A rank becomes invincible when all lower ranking pieces
		// are gone or *known*.
		//
		// Invincibility means that a rank can attack unknown pieces
		// with the prospect of a win or even exchange.
		//
		// Even the Spy can be invincible, if other opponent
		// pieces are suspected but not known.  (If all the
		// other opponent pieces were known, then the Spy
		// would be known by default).
		//
		// TBD: if there is one unknown piece remaining
		// and the others are suspected, then the unknown piece
		// should be suspected as well, but currently is is
		// unknown.
		//
		// Invincible AI Eights, Nines or the Spy in an
		// unknown exchange are EVEN (see winFight())

		for (int c = RED; c <= BLUE; c++) {
			int rank;
			for (rank = 1;rank<15;rank++)
				invincibleRank[1-c][rank-1] = false;

			for (rank = 1;rank <= 10;rank++) {
				if (lowerKnownOrSuspectedRankCount[c][rank-1] < lowerRankCount[c][rank-1])
					continue;
				invincibleRank[1-c][rank-1] = true;
			}

			for (rank = 1;rank<9;rank++)
				if (rankAtLarge(1-c, rank) != 0)
					break;
			invincibleWinRank[c] = rank-1;
		}

		possibleFlag();
	}

	// Scan the board for isolated unmoved pieces (possible bombs).
	// If the piece is Unknown and unmoved
	// (and if the AI does not already suspect
	// the piece to be something else, usually a Flag),
	// reset the piece rank to Bomb so that the AI pieces
	// will not want to attack it.

	// The ai considers all isolated unmoved pieces to be bombs.
	//
	// So one way to fool the AI is to make the flag
	// isolated on the front rank (by moving all pieces that
	// surround it) and then the AI will not attack it until
	// it is the last piece remaining.  I doubt that few
	// opponents will ever realize this without reading this code.
	//

	private void possibleBomb()
	{
		for (int i = 78; i <= 120; i++) {
			if (!Grid.isValid(i))
				continue;
			Piece tp = getPiece(i);
			if (tp != null
				&& tp.getRank() == Rank.UNKNOWN
				&& !tp.hasMoved()) {
				int found = 0;
				if (i != 78 && i != 87)	// top end pieces are often bombs
				for ( int d : dir ) {
					int j = i + d;
					if (!Grid.isValid(j) || d == 11)
						continue;
					Piece p = getPiece(j);

					if (p != null
						&& !(p.hasMoved()
							|| p.isKnown()))
						found++;
				}

	// transform the piece into a suspected bomb.

				if (found == 0)
					tp.setSuspectedRank(Rank.BOMB);
			}
		}
	}

	//
	// Usual flag locations are on the back row, and usually
	// in the corner or beneath the lakes
	// F -- F F -- -- F F -- F
	protected boolean usualFlagLocation(int color, int i)
	{
		if (Grid.getY(i) != Grid.yside(color, 0))
			return false;
		int x = Grid.getX(i);
		if (x == 1
			|| x == 4
			|| x == 5
			|| x == 8)
			return false;
		return true;
	}

	// Scan the board setup for suspected flag pieces.
	//
	// This code is designed primarily to detect the opponent flag,
	// but by also scanning the AI's own pieces, it can second
	// guess the opponent where the opponent may think
	// the AI flag may be located.  This tells the AI whether
	// to step up protection on its own flag, and possibly to
	// step up protection on a alternate location in an effort
	// to create a realistic deception that the flag is at
	// the alternate location, but really isn't.

	protected void possibleFlag()
	{
		for (int c = RED; c <= BLUE; c++) {

	// If the flag is already known (perhaps it is the last piece
	// on the board), skip this code.

		Piece flagp = flag[c];
		if (flagp != null && flagp.isKnown())
			continue;

		int [][] maybe = new int[31][];
		maybe_count[c] = 0;
		open_count[c] = 0;

		int lastbp = 0;
		int bestGuess = 99;
                for ( int[] bp : bombPattern ) {
			int[] b = new int[6];
			for ( int i = 0; bp[i] != 0; i++)
				b[i] = Grid.side(c, bp[i]);
			flagp = getPiece(b[0]);
			if (flagp != null
				&& (!flagp.isKnown()
				 	|| flagp.isSuspectedRank()
				 	|| flagp.getRank() == Rank.FLAG)
				&& !flagp.hasMoved()) {
				boolean open = false;
				int k;
				for ( k = 1; b[k] != 0; k++ ) {
					Piece p = getPiece(b[k]);
					if (p == null
						|| (p.isKnown() && p.getRank() != Rank.BOMB)
						|| p.hasMoved()) {
						if (getSetupRank(b[k]) == Rank.BOMB) {
							open = true;
							continue;
						}
						break;
					}
				} // k

				// b[k] == 0 means possible flag structure

				if (b[k] == 0) {
					if (open) {
						bestGuess = maybe_count[c];
						open_count[c]++;
					}
					maybe[maybe_count[c]++]=b;

		// Note: Adjacent bomb structures are considered to be
		// only one structure, because it only takes one Eight
		// to penetrate.  For example:
		// -- BB BB --
		// BB B7 BF BB
		// appears as two substructures, although only one Eight
		// is necessary.  (Yet, two Eights would be required
		// if any of the outside bombs were actually pieces.)

		// Note: The large classic bomb structure
		// -- -- -- BB
		// -- -- BB B7
		// -- BB B7 BB
		// BB B7 BB BF
		// would never be attacked by the AI, even if it has
		// its full complement of five Eights.  That is
		// because there are a total of six substructures.
		// With the adjacent structure rule, there are
		// four substructures.
					if (bp[0] == lastbp + 1) {
						open_count[c]++;
						lastbp = 0;
					} else
						lastbp = bp[0];
				}

			} // possible flag
		} // bombPattern

		if (maybe_count[c] >= 1) {

		// Mark surrounding pieces in all usual flag
		// structures as suspected bombs.

			for (int i = 0; i < maybe_count[c]; i++)
				if (usualFlagLocation(c, maybe[i][0]))
					genDestBombedFlag(maybe, maybe_count[c], open_count[c], i);

		// Pick the stucture that looks most likely and
		// mark it as containing the flag.

			if (bestGuess == 99)
				bestGuess = getBestGuess(maybe, maybe_count[c]);

			genDestBombedFlag(maybe, maybe_count[c], open_count[c], bestGuess);
			if (c == Settings.bottomColor) {
				flag[c] = getPiece(maybe[bestGuess][0]);
				flag[c].setSuspectedRank(Rank.FLAG);
			}

		} else if (c == Settings.bottomColor) {

		// Player color c did not surround his flags with
		// adjacent bombs.  That does not mean the player did
		// not bomb the flag, but perhaps surrounded the flag
		// along with some lowly piece(s) like a Seven.
		// Common setups are:
		// Setup 1:
		// B - -
		// 7 B -
		// F 7 B
		//
		// Setup 2:
		// - B B -
		// B F 7 B
		//
		// These setups are attacked using the standard
		// bomb patterns.  Setup 1 contain 3 patterns.
		// This is the toughest for the AI to crack, because once
		// it removes one bomb and one Seven takes its Eight,
		// the AI will think that the remaining Seven is the Flag,
		// which is certainly possible.
		// Setup 3:
		// B - -
		// 7 B -
		// 7 F B
		//
		// Setup 2 contain 2 patterns.  If the bomb above the
		// Seven is removed and the Seven takes the Eight, the
		// AI will not recognize any bomb pattern .
		//
		// But the AI will choose which of the pieces to target
		// as a flag on the back row based on where any bombs
		// on the second to back row were removed.  The AI
		// will choose the piece directly behind the bomb or
		// next to that piece, if that piece is against the side
		// or has an unmoved or bomb piece next to it.
		// For example, in Setup 1, if the middle bomb is removed,
		// and a Seven takes an Eight, there is still
		// one matching pattern, so the AI will target the unmoved
		// Seven.
		// B - -
		// - 7 -
		// F 7 B
		//
		// But if that Seven moves, then the Flag is targeted
		// (rather than the Bomb or any other back row piece)
		// because the piece is against the side of the board.
		//
		// In Setup 2, if the bomb above the Seven is removed,
		// and the Seven takes the Eight, there are no matching
		// patterns.
		// - B 7 -
		// B F - B
		//
		// The next piece to be targeted is the Flag, because it
		// is next to another unmoved piece.
		//
		// Once this rule is exhausted,
		// go for any remaining unmoved pieces.

			flagp = null;
			for (int x=1; x <= 8; x++) {
				int i = Grid.getIndex(x, Grid.yside(c,1));
				if (getSetupRank(i) == Rank.BOMB) {
					int flagi = Grid.getIndex(x, Grid.yside(c,0));
					Piece flag = getPiece(flagi);
					if (flag != null
						&& (!flag.isKnown() || flag.isSuspectedRank())
						&& !flag.hasMoved()) {
						flagp = flag;
						break;
					}
					flag = getPiece(flagi-1);
					if (flag != null
						&& (!flag.isKnown() || flag.isSuspectedRank())
						&& !flag.hasMoved()) {
						if (x == 1) {
							flagp = flag;
							break;
						}
						Piece p = getPiece(flagi-2);
						if (p != null && (!p.isKnown() || p.getRank() == Rank.BOMB) && !p.hasMoved()) {
							flagp = flag;
							break;
						}
					}

					flag = getPiece(flagi+1);
					if (flag != null
						&& (!flag.isKnown() || flag.isSuspectedRank())
						&& !flag.hasMoved()) {
						if (x == 8) {
							flagp = flag;
							break;
						}
						Piece p = getPiece(flagi+2);
						if (p != null && (!p.isKnown() || p.getRank() == Rank.BOMB) && !p.hasMoved()) {
							flagp = flag;
							break;
						}
					}
				}
			}


		// Try the back rows first.
		// Choose the piece that has the most remaining
		// surrounding pieces.
		// (water counts, so corners and edges are preferred)

			for (int y=0; y <= 3 && flagp == null; y++)  {
			int flagprot = 0;
			for (int x=0; x <= 9; x++) {
				int i = Grid.getIndex(x, Grid.yside(c,y));
				Piece p = getPiece(i); 
				if (p != null
					&& (!p.isKnown() || p.isSuspectedRank())
					&& !p.hasMoved()) {
					int count = 0;
					for (int d : dir) {
						Piece bp = getPiece(i+d);
						if (bp == null
							|| bp.getColor() == c - 1)
							continue;
						if (!Grid.isValid(i+d))
							count++;
						else if (bp.hasMoved())
							count+=2;
						else
							count+=3;
					}
					if (flagprot == 0 || count > flagprot) {
						flagp = p;
						flagprot = count;
					}
				}
			}
			}

	// flagp is null if the AI has just captured the flag
	// with the last move.
	// assert flagp != null : "Well, where IS the flag?";

			if (flagp != null) {
				flag[c] = flagp;
				flagp.setSuspectedRank(Rank.FLAG);
			}

		} // maybe == 0 (opponent flag only)

		} // color c
	}

	int getBestGuess(int[][] maybe, int maybe_count)
	{
		int size = 0;
		int bestGuess = 99;
		int bestGuessGuards = 0;
		for (int i = 0; i < maybe_count; i++) {

			int nb;

		// compute the number of bombs in the structure

			for (nb = 1; maybe[i][nb] != 0; nb++) {

		// If the one of the possible bombs has a chase rank
		// (meaning that it protected some piece under attack),
		// it is much less likely that the piece is actually
		// a bomb, unless the player is a bluffer.

				Piece p = getPiece(maybe[i][nb]);
				if (!p.isKnown()
					&& p.getActingRankChase() != Rank.NIL) {
					nb = 10;
					break;
				}
			}
			nb--;

		// patterns closest to the back row are preferred
			nb += i;

			int start = i;
			while (i < maybe_count - 1
				&& Math.abs(maybe[i][0] - maybe[i+1][0]) == 1) {
				i++;
				nb++;
			}


		// The AI also looks at possible sentries
		// near the structures.  If the opponent is leaving
		// a structure undefended, it likely is a ruse,
		// regardless of its composition.
		//
		// A structure is guarded when a movable sentry
		// can reach the flag structure before any possible attacker.
		// Yet if the attacker is far away, it is difficult
		// to tell which structure could hold the flag, because the
		// sentry could defend any number of structures.  So unless
		// the attacker is close, the flag structure is difficult
		// to discern.
		//
		// Note that attackers are also counted.
		// This is because once the AI is able to penetrate,
		// perhaps with superior pieces, the sentries will
		// be forced away.  For example,
		// | R8 R1 --
		// | BB B3 BB
		// | BF BB B?
		// |xxxxxxxxx
		//
		// After R1xB3, the sentry is gone.  This does not
		// mean that the flag is elsewhere.  So the AI
		// must continue with R8xBB and R8xBF.
		//
		// Sentries and attackers are counted within 1 or 2 steps
		// of the flag:
		// | S 
		// | A S
		// | B A S
		// | F B A S
		// ---------
		//
		// Once an AI attacker is in reach of multiple structures,
		// the AI needs to focus on the one that is actively defended.
		// What usually happens now, is that a sentry is drawn off
		// the flag structure to thwart the attacker, so then
		// the pattern no longer matches.  This makes it look like
		// the flag structure is unguarded, and thus the AI attacks
		// the structure that it can get to, which
		// is always the ruse.
		//
		// TBD: So the AI also needs to look for a defender between the flag
		// structure and an AI unknown or Eight.  If there is a defender,
		// the flag structure is still guarded, even if it there are no
		// sentries adjacent to the flag structure.

		// Note: If there is a corner bomb structure (2 bombs) and
		// a 3 bomb structure, the 3 bomb structure normally
		// contains the flag because often the corner bomb
		// structure is a ruse because it is difficult to defend.
		// However, the corner bomb structure could be inside
		// a layered corner bomb structure.  In this case,
		// the corner bomb structure probably has the flag.
		// So the AI looks for a combination of small size and
		// large number of defenders.

			Piece flag = getPiece(maybe[start][0]);
			int guards = grid.movablePieceCount(flag.getColor(), maybe[start][0], 2)*2
				+ grid.movablePieceCount(1-flag.getColor(), maybe[start][0], 1);

			if (size == 0
				|| guards > bestGuessGuards
				|| nb < size) {
				bestGuess = start;
				size = nb;
				bestGuessGuards = guards;
			}
		} // i
		return bestGuess;
	}


	private void genDestBombedFlag(int[][] maybe, int maybe_count, int open_count, int bestGuess)
	{
		Piece flagp = getPiece(maybe[bestGuess][0]);
		int color = flagp.getColor();

		int eightsAtLarge = rankAtLarge(1-color, Rank.EIGHT);

		// Eights begin to attack bomb structures as the
		// number of possible structures diminish
		boolean eightAttack = (maybe_count <= 3
			|| maybe_count - open_count <= eightsAtLarge);

		// It doesn't matter if the piece really is a bomb or not.
		isBombedFlag[color] = true;
		for (int j = 0; maybe[bestGuess][j] != 0; j++) {
			assert Grid.isValid(maybe[bestGuess][j]) : maybe[bestGuess][j] + " is not valid ";
			Piece p = getPiece(maybe[bestGuess][j]);
			if (p == null
				|| !(p.getRank() == Rank.BOMB
					|| p.getRank() == Rank.FLAG
					|| p.getRank() == Rank.UNKNOWN)
				|| p.hasMoved()) {
				isBombedFlag[color] = false;
				continue;
			}

			if (color == Settings.bottomColor) {

		// Note: the AI marks the pieces surrounding
		// the suspected flag as suspected bombs.  The AI
		// needs to be relatively confident that these
		// pieces are bombs, because it will risk approaching
		// these pieces if the reward is large enough.
		// (See riskOfLoss()).

				if (eightAttack
					&& p.getRank() != Rank.BOMB) {

		// If there is only 1 pattern left, the AI goes out
		// on a limb and decides that the pieces in the
		// bomb pattern are known bombs.  This eliminates them
		// from the piece move lists, which means that
		// they offer no protective value to pieces that
		// bluff against lower ranked pieces.  This code
		// works for both AI and opponent bomb patterns.

					if (maybe_count == 1) {
						p.makeKnown();
						grid.clearMovablePiece(p);
						p.setSuspectedRank(Rank.BOMB);

		// If the piece already has a suspected rank, keep it,
		// otherwise make it a suspected bomb

					} else if (p.getRank() == Rank.UNKNOWN)
						p.setSuspectedRank(Rank.BOMB);

				}
			} else if (p.getRank() == Rank.BOMB
				&& maybe_count == 1) {
					p.makeKnown();
					grid.clearMovablePiece(p);
				}
		} // j
	}
	// ********* end of suspected ranks

	public boolean move(Move m)
	{
		if (getPiece(m.getTo()) != null)
			return false;

		if (validMove(m))
		{
			Piece fp = getPiece(m.getFrom());
			moveHistory(fp, null, m.getMove());

			clearPiece(m.getFrom());
			setPiece(fp, m.getTo());
			fp.setMoved();
			if (Math.abs(m.getToX() - m.getFromX()) > 1 || 
				Math.abs(m.getToY() - m.getFromY()) > 1) {
				//scouts reveal themselves by moving more than one place
				fp.setShown(true);
				fp.setRank(Rank.NINE);
				fp.makeKnown();
			}
			genChaseRank(fp.getColor());
			genFleeRank(fp, null);	// depends on suspected rank
			return true;
		}
		
		return false;
	}

	public void moveToTray(Piece p)
	{
		p.kill();

		remove(p);
	}
	
	public boolean remove(int i)
	{
		Piece p = getPiece(i);
		if (p == null)
			return false;
		remove(p);
		clearPiece(i);
		return true;
	}

	public void remove(Piece p)
	{
		tray.add(p);
		Collections.sort(tray,new Comparator<Piece>(){
				     public int compare(Piece p1,Piece p2){
					return p1.getRank().ordinal() - p2.getRank().ordinal();
				     }});
	}

	public boolean remove(Spot s)
	{
		return remove(grid.getIndex(s.getX(), s.getY()));
	}

	// Three Moves on Two Squares: Two Square Rule.
	//
	// (ISF 10.1)
	// It is not allowed to move a piece more than 3
	// times non-stop between the same two
	// squares, regardless of what the opponent is doing.
	// It does not matter whether a piece is
	// moving and thereby attacking an opponent‟s
	// piece, or just moving to an empty square.
	//
	// Thus, even if the to square is becomes occupied
	// by an opponent piece, the player is not allowed
	// to attack it, if it would cause this rule to be violated.
	//
	public boolean isTwoSquares(int m)
	{
		int size = undoList.size();
		if (size < 6)
			return false;

		// AI always abides by Two Squares rule
		// even if box is not checked (AI plays nice).
		if (Settings.twoSquares
			|| getPiece(Move.unpackFrom(m)).getColor() == Settings.topColor) {
			UndoMove prev = undoList.get(size-2);
			if (prev == null)
				return false;
			UndoMove prevprev = undoList.get(size-6);
			if (prevprev == null)
				return false;
			return prevprev.equals(prev)
				&& Move.unpackFrom(m) == prev.getTo()
				&& Move.unpackTo(m) == prev.getFrom();
		} else
			// let opponent get away with it
			return false;
	}

	// Repetition of Threatening Moves: More-Squares Rule
	// It is not allowed to continuously chase one or
	// more pieces of the opponent endlessly. The
	// continuous chaser may not play a chasing
	// move which would lead to a position on the
	// board which has already taken place.

	// return TRUE if cyclic move
	public boolean isMoreSquares()
	{
		int size = undoList.size();
		if (size < 2)
			return false;
		UndoMove aimove = undoList.get(size-1);
		UndoMove oppmove = undoList.get(size-2);

		for (int d : dir) {
			int i = oppmove.getTo() + d;
			if (!Grid.isValid(i))
				continue;
			if (i == aimove.getTo()) {
				// chase confirmed
				return isRepeatedPosition();
			}
			// we are being chased, so repetitive moves OK
			if (i == aimove.getFrom())
				return false;
		}
		// no chases
		// return false;
		// prevent looping
		return isRepeatedPosition();
	}

	// Because of limited search depth, the completion of
	// a two squares ending may well be beyond the horizon of the search.
	// However, a two squares ending can be predicted earlier if the
	// player begins a possible two squares ending before the defender.
	// For example,
	// xx xx xx	xx xx xx	xx xx xx
	// -- R3 R?	-- R3 R?	-- R3 R?
	// -- -- --	-- -- B2	B2 -- --
	// -- B2 --	-- -- --	-- -- --
	//
	// Blue Two has the move and approaches Red Three
	// Positions 1 & 2 can lead to the two squares rule but
	// position 3 does not.  Thus, move generation can eliminate
	// the move for R3 back to its original spot if the player
	// was the first to move its piece between two adjacent squares.
	//
	// Hence, the effect of the two squares rule can be felt
	// at ply 3 rather than played out to its entirety at ply 7.
	//
	// If a two squares ending is predicted, the AI will see
	// the inevitable loss of its piece, and may play some other 
	// move, because it sees that it is pointless to move its
	// chased piece away.
	//
	// This rule also works for attacks by the AI on opponent
	// pieces, allowing it to see the effect of the two squares
	// rule much earlier in the search tree.
	//
	// Note that this rule works even if chaser and chased pieces
	// are far away.  For example,
	// xx xx xx
	// -- R3 R?
	// -- -- --
	// B2 -- --
	// Blue has the move. This is not a possible two squares ending
	// because if Blue Two moves right, Red Three moves left,
	// Blue Two moves left, Red Three can still move right,
	// because Blue began the two squares first.
	// ...    2a3-b3	// m3
	// 3b1-a1		// m2
	// ...    2b3-a3	// m1
	// 3a1-b1	// allowed because m1 and m3 are swapped

	// ...    2a3-b3	// m5
	// 3b1-a1		// m4
	// ...    2b3-a3	// m3
	// 3a1-b1		// m2
	// ...    2a3-b3	// m1
	// 3b1-a1	// allowed because m1 and m3 are swapped

	// ...    <some other move>	// m3
	// 3b1-a1		// m2
	// ...    2b3-a3	// m1
	// 3a1-b1	// disallowed because m1 and m3 are not swapped

	public boolean isPossibleTwoSquares(int m)
	{
		UndoMove m2 = getLastMove(2);
		if (m2 == null)
			return false;

		int from = Move.unpackFrom(m);
		int to = Move.unpackTo(m);

		// not back to the same square?
		if (m2.getFrom() != to)
			return false;

		// was a capture?
		// For example,
		// -- R6 -- --
		// xx B5 R4 xx
		// xx -- -- xx
		// -- -- B3 --
		// Blue Three moves up to attack Red Four.  R4xB5.
		// Blue Three moves left.  Return false to allow
		// Red Four to return to its initial square.
		// (Otherwise Red Four would not take Blue Five
		// and flee upwards instead).
		if (m2.tp != null)
			return false;

		// not the same piece?
		if (!m2.getPiece().equals(getPiece(from)))
			return false;

		// not an adjacent move (i.e., nine far move)
		if (!Grid.isAdjacent(m))
			return false;

		// If the target square has an opposite escape square,
		// the code must allow the move.  For example,
		// xx xx xx xx
		// -- R3 -- R?
		// -- -- -- --
		// -- B2 -- --
		//
		// Blue Two has the move and approaches Red Three.
		// If R3 moves to the left, it must be allowed to return
		// back to its original position, because the target
		// square has an escape route.
		//
		// Version 9.5 added code to handle the following case.
		// R? -- -- --
		// -- R3 R? --
		// -- -- -- --
		// -- B2 -- --
		//
		// The idea was to allow R3 to move back right after
		// it moved left.  But this is a pointless move, and
		// the transposition table erroneously returns the value
		// from the stored position when B2 tries to move back
		// to the right to chase R3.  But that stored value is
		// not valid, because R3 began the two squares sequence
		// and will be forced to use the escape route next.
		// For example,
		// R? -- R? --
		// -- R3 R? --
		// -- -- -- --
		// -- B2 -- --
		// Because R3 will be forced to use the escape square above
		// it, it will soon become trapped, with a very negative value.
		// So Version 9.6 removed the 9.5 code and replaced it
		// with the 9.3 test for three squares.

		// Test for three squares (which is allowed)

		// Version 9.6 allowed the move if the third square
		// was occupied by an opponent piece, because then
		// the chased piece can avoid a two squares ending
		// by attacking the opponent piece.
		//
		// Version 9.7 commented that line of code out:
		//	|| p.getColor() == 1 - m2.getPiece().getColor()
		// because the opponent piece *could* be a bomb.
		// Alternatively, the code could be qualified by
		//	&& p.getRank() != Rank.BOMB))

		Piece p = getPiece(to + to - from);
		if (p == null)
			return false;


		UndoMove oppmove1 = getLastMove(1);
		if (oppmove1 == null)
			return false;

		// fleeing is OK, even if back to the same square
		// for example,
		// -------
		// R? R5 |
		// R? -- |
		// B4 -- |
		// Red Five moves down and Blue Four moves right.
		// It is OK for Red Five to return to its original
		// square.
		if (from - (to - from) == oppmove1.getTo())
			return false;

		// This rule checks if the player move is forced.  If the
		// player is returning to the same square and the
		// opponent was doing something else on the board,
		// it could still lead to a two squares result, but
		// no two squares prediction can be made because the move
		// was not forced.  For example,
		// -- R9 xx
		// B4 -- B1
		// -- -- --
		// -- -- B? 
		// Unknown but suspected Blue One moves down.  Red Nine
		// moves down two squares to begin a two squares sequence
		// to be able to attack Blue One and confirm its identity.
		// When the move back to its original square is considered,
		// isPossibleTwoSquares() returns false because Blue's
		// first move was not forced, and in order to force
		// Blue to move back down, Red Nine would have to move
		// up one square, subjecting it to attack by Blue Four.
		//
		// Compare with the following two squares attack.
		//
		// |-- R3 --
		// |-- -- --
		// |-- B4 B?
		// |-- -- B?
		// |xxxxxxxxxxx
		// Blue Four moves to the left.
		// Now if Red Three moves to the left, and
		// then Blue Four tries to move back right,
		// isPossibleTwoSquares should return true.
		// (Thus Blue Four is seen to be trapped in very few ply).
		// 

		if (!Grid.isAlternatingMove(m, oppmove1))
			return false;

		UndoMove oppmove3 = getLastMove(3);
		if (oppmove3 == null)
			return false;

		// player began two moves before opponent?

		if (!(oppmove3.getFrom() == oppmove1.getTo()
			&& oppmove3.getTo() == oppmove1.getFrom()))
			return true;

		UndoMove m4 = getLastMove(4);
		if (m4 == null)
			return false;

		if (!(m4.getFrom() == m2.getTo()
			&& m4.getTo() == m2.getFrom()))
			return false;

		UndoMove oppmove5 = getLastMove(5);
		if (oppmove5 == null)
			return false;

		return !(oppmove5.getFrom() == oppmove3.getTo()
			&& oppmove5.getTo() == oppmove3.getFrom());

	}

	public boolean isChased(int m)
	{
		UndoMove oppmove = getLastMove(1);
		if (oppmove == null)
			return false;
		return Grid.isAdjacent(oppmove.getTo(),  Move.unpackFrom(m));
	}

	// A chasing move back to the square where the chasing piece
	// came from in the directly preceding turn is allowed
	// if this can force the opponent piece to hit the
	// Two-Squares Rule / Five-Moves-on-Two-Squares Rule first.
	// If not, then the chase move should be discarded if it
	// results in a repetitive position, because there is no point
	// in chasing further.  (When this function returns false,
	// the calling code can reduce the number of
	// pointless back-and-forth moves from three to two, because
	// the third move results in the same position.)
	// 
	// Note: Piece does not need to be adjacent for a chase.
	// For example,
	// -- -- R9
	// -- -- xx
	// BS -- xx
	// -- -- --
	// Red Nine moves left two squares, Blue Spy moves right,
	// Red Nine moves right, Blue Spy moves left.
	// Red Nine should be allowed to move right again,
	// even though this is a repeated position, because
	// it does not violate the two squares rule and
	// Red Nine will capture Blue Spy.
	//
	// Note: A chase can begin without moving the chase piece.
	// For example,
	// -- R9 --
	// -- R4 --
	// -- -- --
	// -- -- xx
	// -- BS xx
	// Red Four moves right.  Blue Spy moves left.  Red Nine
	// can eventually capture Blue Spy by moving to the right.

	public boolean isTwoSquaresChase(int m)
	{
		Move m2 = getLastMove(2);
		if (m2 == null)
			return false;

		// not back to square where the chasing piece came from?
		if (m2.getFrom() != Move.unpackTo(m))
			return false;

		// If opponent piece does not move between same two squares,
		// then this move cannot result in a two squares victory.
		Move m1 = getLastMove(1);
		if (m1 == null)
		 	return false;

		Move m3 = getLastMove(3);
		if (m3 == null)
		 	return false;

		if (m1.getTo() != m3.getFrom()
			|| m1.getFrom() != m3.getTo())
			return false;

		// Will the AI eventually be blocked by Two Squares?
		//
		// B3 --	B3 --		-- B3		-- B3
		// -- R2	R2 --		R2 --		-- R2
		// (A)		  (1)		  (B)		  (2)
		// (C)		  (3)		  (D)		  (4)
		//
		// (A) is the starting position.
		// The AI is allowed to make moves 1 and 2.
		// However, if position C is reached, the AI is not
		// not allowed to make move 3, although this is
		// a legal move.  This reduces 
		// pointless 3 move sequences to pointless 2 move
		// sequences.
		//
		// If A was indeed the starting position,
		// this function will return false when position C is reached.
		// isRepeatedPosition() will prevent the AI from
		// considering the move.
		// If position (A) was not the starting position,
		// this function returns true
		// because the moves (1) and (3) are not equal.
		// The AI is allowed to proceed, because
		// it will be the opponent whose will repeat first.
		//
		// Hence, position D can only be reached
		// if the opponent is the repeater.  So if moves 1 and 3
		// are equal, as well as the proposed move 4 equal to move 2,
		// this function returns true, which
		// then AI allows to proceed,
		// by avoiding the isRepeatedPosition() check in the AI.

		UndoMove m4 = getLastMove(4);
		if (m4 == null)
			return false;

		Move m5 = getLastMove(5);
		if (m5 == null)
			return false;

		// (1) If the proposed move and the move four plies ago
		// are equal,
		// (2) and the current position and the position four plies ago
		// are equal,
		// (3) and the prior opponent move and move five plies ago
		// are not equal,
		// (if they are equal, it means we are at position D
		// rather than at position C)
		// -- then this is a repetitive move
		if (m == m4.getMove()
			&& boardHistory[bturn].hash == m4.hash
			&& !m5.equals(m1))	// compare Move, not UndoMove
			return false;

		return true;
	}

	// This implements the More-Squares Rule during tree search,
	// but is more restrictive because it also eliminates
	// all repetitive moves,
	// non-chase moves as well as chase moves.
	// It is used during move generation to forward prune
	// repeated moves.
	//
	// Note that a player chasing an opponent piece
	// can reach a previous position
	// and still abide by More-Squares Rule, as long as the
	// moves are not continuous.
	//
	public boolean isRepeatedPosition()
	{
		// Because isRepeatedPosition() is more restrictive
		// than More Squares, the AI
		// does not expect the opponent to abide
		// by this rule as coded.

		// Note that the hash is XORed with 1 - undoList.size()%2
		// This tests if the opposing player
		// has seen the position that the
		// current player has just created.
		// 
		UndoMove entry = boardHistory[bturn].get();
		if (entry == null)
			return false;

		// Position has been reached before

		return true;
	}

	public void undoLastMove()
	{
		int size = undoList.size();
		if (size < 2)
			return;

		boardHistory[0].remove();
		boardHistory[1].remove();

		for (int j = 0; j < 2; j++, size--) {
			UndoMove undo = undoList.get(size-1);
			Piece fp = undo.getPiece();
			Piece tp = getPiece(undo.getTo());
			fp.copy(undo.fpcopy);
			setPiece(undo.getPiece(), undo.getFrom());
			setPiece(undo.tp, undo.getTo());
			if (undo.tp != null) {
				undo.tp.copy(undo.tpcopy);
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

	protected boolean validAttack(Move m)
	{
		if (!Grid.isValid(m.getTo()) || !Grid.isValid(m.getFrom()))
			return false;
		if (getPiece(m.getTo()) == null)
			return false;
		if (getPiece(m.getFrom()) == null)
			return false;
		if (getPiece(m.getFrom()).getColor() ==
			getPiece(m.getTo()).getColor())
			return false;

		return validMove(m);
	}

	// TRUE if piece moves to legal square
	public boolean validMove(Move m)
	{
		if (!Grid.isValid(m.getTo()) || !Grid.isValid(m.getFrom()))
			return false;
		Piece fp = getPiece(m.getFrom());
		if (fp == null)
			return false;
		
		//check for rule: "a player may not move their piece back and fourth.." or something
		if (isTwoSquares(m.getMove()))
			return false;

		switch (fp.getActualRank())
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

		if (fp.getActualRank().equals(Rank.NINE)
			|| fp.getActualRank().equals(Rank.UNKNOWN))
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

	public int unknownRankAtLarge(int color, int r)
	{
		return Rank.getRanks(Rank.toRank(r))
			- trayRank[color][r-1]
			- knownRank[color][r-1];
	}

	public int unknownRankAtLarge(int color, Rank rank)
	{
		return unknownRankAtLarge(color, rank.ordinal());
	}

	public int knownRankAtLarge(int color, int r)
	{
		return knownRank[color][r-1];
	}

	public int knownRankAtLarge(int color, Rank rank)
	{
		return knownRankAtLarge(color, rank.ordinal());
	}

	public int rankAtLarge(int color, int rank)
	{
		return (Rank.getRanks(Rank.toRank(rank)) - trayRank[color][rank-1]);
	}

	public int rankAtLarge(int color, Rank rank)
	{
		return rankAtLarge(color, rank.ordinal());
	}

        public boolean hasSpy(int color)
        {
                return rankAtLarge(color, Rank.SPY) != 0;
        }

	public boolean isInvincible(int color, int r) 
	{
		return invincibleRank[color][r-1];
	}

	public boolean isInvincible(Piece p) 
	{
		Rank rank = p.getRank();
		return isInvincible(p.getColor(), rank.ordinal());
	}

	public boolean isInvincibleDefender(Piece p) 
	{
		Rank rank = p.getRank();
		if (rank == Rank.ONE && hasSpy(1-p.getColor()))
			return false;

		return isInvincible(p.getColor(), rank.ordinal());
	}

	public long getHash()
	{
		return boardHistory[bturn].hash;
	}

	protected Piece getSetupPiece(int i)
	{
		return setup[i];
	}

	protected Rank getSetupRank(int i)
	{
		Piece p = setup[i];
		if (!p.isKnown())
			return Rank.UNKNOWN;
		else
			return p.getRank();
	}

	protected void revealRank(Piece p)
	{
		if (p.getColor() == Settings.bottomColor
			&& !p.isKnown()
			&& p.getRank().ordinal() <= 4) {

	// its hard to distinguish a Four from a Five,
	// so if the AI thinks a piece is a Four and it turns
	// out to be a Five, nothing is changed

			if (p.getRank() == Rank.FOUR
				&& p.getActualRank() == Rank.FIVE) {}
			else if (p.getRank().ordinal() >= p.getActualRank().ordinal())
				guessedRankCorrect++;
			else
				guessedRankCorrect--;
			blufferRisk = 5 - guessedRankCorrect;
			blufferRisk = Math.max(blufferRisk, 2);
			blufferRisk = Math.min(blufferRisk, 5);
		}
		p.revealRank();
	}
}
