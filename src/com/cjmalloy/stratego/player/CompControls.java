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

import com.cjmalloy.stratego.Move;
import com.cjmalloy.stratego.Piece;
import com.cjmalloy.stratego.Spot;

public interface CompControls
{
	public void aiReturnMove(Move m);
	public void aiReturnPlace(Piece p, Spot s);
	public void play();
}
