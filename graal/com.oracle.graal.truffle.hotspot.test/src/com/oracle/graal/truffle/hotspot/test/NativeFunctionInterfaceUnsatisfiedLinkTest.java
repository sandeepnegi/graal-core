/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.truffle.hotspot.test;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.nfi.NativeFunctionInterfaceRuntime;
import com.oracle.nfi.api.NativeFunctionInterface;

public class NativeFunctionInterfaceUnsatisfiedLinkTest {

    @Before
    public void setUp() {
        // Ignore on SPARC
        Assume.assumeFalse(System.getProperty("os.arch").toUpperCase().contains("SPARC"));
    }

    @Test
    public void testNotFound() {
        NativeFunctionInterface nfi = NativeFunctionInterfaceRuntime.getNativeFunctionInterface();

        try {
            nfi.getLibraryHandle("truffle_test_library_should_not_exist.so");
            Assert.fail();
        } catch (UnsatisfiedLinkError e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().indexOf("truffle_test_library_should_not_exist.so") != -1);
        }
    }

}
