/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins.chroot.util;

import hudson.FilePath;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import static org.fest.assertions.Assertions.assertThat;

/**
 *
 * @author rmohr
 */
public class ChrootUtilTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    public ChrootUtilTest() {
    }

    @Test
    public void testSplit() {
        String packages = " a b, c ; d,e;f    g,h\ti\nj\r\nk ";
        String[] lst = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"};
        assertEquals(Arrays.asList(lst), ChrootUtil.splitPackages(packages));
    }
}
