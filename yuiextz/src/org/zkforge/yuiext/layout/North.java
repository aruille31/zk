/* North.java

 {{IS_NOTE
 Purpose:
 
 Description:
 
 History:
 Aug 7, 2007 6:18:04 PM , Created by jumperchen
 }}IS_NOTE

 Copyright (C) 2007 Potix Corporation. All Rights Reserved.

 {{IS_RIGHT
 This program is distributed under GPL Version 2.0 in the hope that
 it will be useful, but WITHOUT ANY WARRANTY.
 }}IS_RIGHT
 */
package org.zkforge.yuiext.layout;

/**
 * This component is a north region.
 * 
 * @author jumperchen
 * 
 */
public class North extends SplitLayoutRegion {

	private int[] _cmargins = new int[] { 2, 0, 0, 2 };

	/**
	 * defaults to {top: 2, left: 0, right: 0, bottom: 2}
	 */
	public int[] getCmargins() {
		return _cmargins;
	}

	public void setCmargins(int top, int left, int right, int bottom) {
		boolean f = false;
		if (_cmargins[0] != top) {
			f = true;
			_cmargins[0] = top;
		}
		if (_cmargins[1] != left) {
			f = true;
			_cmargins[1] = left;
		}
		if (_cmargins[2] != right) {
			f = true;
			_cmargins[2] = right;
		}
		if (_cmargins[3] != bottom) {
			f = true;
			_cmargins[3] = bottom;
		}
		if (f)
			smartUpdate("z.cmargins", _cmargins[0] + "," + _cmargins[1] + ","
					+ _cmargins[2] + "," + _cmargins[3]);
	}

	/**
	 * Returns {@link BasicLayoutRegion#POSITION_NORTH}
	 */
	public String getPosition() {
		return POSITION_NORTH;
	}

}
