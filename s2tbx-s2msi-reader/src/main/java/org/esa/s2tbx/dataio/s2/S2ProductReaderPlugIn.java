/*
 *
 * Copyright (C) 2013-2014 Brockmann Consult GmbH (info@brockmann-consult.de)
 * Copyright (C) 2014-2015 CS SI
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *  This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 *
 */

package org.esa.s2tbx.dataio.s2;

import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.util.io.SnapFileFilter;

import java.io.File;
import java.util.regex.Pattern;

/**
 * @author Norman Fomferra
 */
public abstract class S2ProductReaderPlugIn implements ProductReaderPlugIn {

    protected final static String REGEX = "(S2A|S2B|S2_)_([A-Z|0-9]{4})_([A-Z|0-9|_]{4})([A-Z|0-9|_]{6})_([A-Z|0-9|_]{4})_([0-9]{8}T[0-9]{6})_.*";
    protected final static Pattern PATTERN = Pattern.compile(REGEX);
    protected final static String FORMAT_NAME = "SENTINEL-2-MSI";

    protected String getFormatName() {
        return FORMAT_NAME;
    }

    @Override
    public Class[] getInputTypes() {
        return new Class[]{String.class, File.class};
    }


    @Override
    public String[] getDefaultFileExtensions() {
        return new String[]{S2Config.MTD_EXT};
    }

    @Override
    public SnapFileFilter getProductFileFilter() {
        return new SnapFileFilter(null,
                getDefaultFileExtensions(),
                "Sentinel-2 MSI product or tile");
    }
}
