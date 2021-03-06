/**
 * Copyright (c) 2010-2019 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.yeelight.internal.lib.enums;

/**
 * @author Coaster Li - Initial contribution
 */
public enum DeviceAction {
    open,
    close,
    brightness,
    color,
    colortemperature,
    increase_bright,
    decrease_bright,
    increase_ct,
    decrease_ct;

    private String mStrValue;
    private int mIntValue;

    public void putValue(String value) {
        this.mStrValue = value;
    }

    public void putValue(int value) {
        this.mIntValue = value;
    }

    public String strValue() {
        return mStrValue;
    }

    public int intValue() {
        return mIntValue;
    }
}
