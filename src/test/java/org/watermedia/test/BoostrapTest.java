package org.watermedia.test;

import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;


public class BoostrapTest {
    public static void main(String[] args) {
        WaterMedia.start("Test", null, null, true);
    }
}
