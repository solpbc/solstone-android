// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.validation.rogbid;

import app.solstone.core.pl.DirectEndpoint;
import app.solstone.core.pl.DirectPairLink;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PlLinkClientPairPolicyTest {
    @Test
    public void delegatesRecognitionToCorePolicy() {
        assertTrue(PlLinkClient.looksLikePairLink("https://go.solstone.app/p#abc"));
        String retiredHost = String.join(".", "link", "solpbc", "org");
        assertFalse(PlLinkClient.looksLikePairLink("https://" + retiredHost + "/p#abc"));
        assertFalse(PlLinkClient.looksLikePairLink("https://example.invalid/p#abc"));
    }

    @Test
    public void delegatesV04AdmissionAndRefusalToCorePolicy() {
        DirectPairLink admitted = PlLinkClient.parseCoreDirectPairLink(
                v04Link(new byte[] {10, 1, 2, 3}));
        assertEquals("10.1.2.3", admitted.getHost());

        assertThrows(
                IllegalArgumentException.class,
                () -> PlLinkClient.parseCoreDirectPairLink(v04Link(new byte[] {8, 8, 8, 8})));
    }

    @Test
    public void delegatesV05LoopbackWholeLinkLimitAndDeduplicationToCorePolicy() {
        DirectPairLink admitted = PlLinkClient.parseCoreDirectPairLink(
                v05Link(
                        new byte[] {127, 0, 0, 1},
                        new byte[] {10, 0, 0, 2},
                        new byte[] {127, 0, 0, 1}));
        assertEquals(
                Arrays.asList(
                        new DirectEndpoint("127.0.0.1", 7657),
                        new DirectEndpoint("10.0.0.2", 7657)),
                admitted.getCandidates());

        assertThrows(
                IllegalArgumentException.class,
                () -> PlLinkClient.parseCoreDirectPairLink(
                        v05Link(new byte[] {10, 0, 0, 2}, new byte[] {(byte) 192, 0, 2, 42})));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlLinkClient.parseCoreDirectPairLink(
                        v05Link(
                                new byte[] {10, 0, 0, 1},
                                new byte[] {10, 0, 0, 2},
                                new byte[] {10, 0, 0, 3},
                                new byte[] {10, 0, 0, 4},
                                new byte[] {10, 0, 0, 5})));
    }

    @Test
    public void rogbidSelectsFirstParsedV05Candidate() {
        DirectPairLink link = PlLinkClient.parseCoreDirectPairLink(
                v05Link(new byte[] {10, 0, 0, 2}, new byte[] {10, 0, 1, 2}));

        assertEquals(new DirectEndpoint("10.0.0.2", 7657), link.endpoint());
    }

    private static String v04Link(byte[] ip) {
        byte[] bytes = new byte[40];
        bytes[0] = 0x04;
        bytes[1] = 0x01;
        System.arraycopy(ip, 0, bytes, 2, 4);
        bytes[6] = 0x1d;
        bytes[7] = (byte) 0xe9;
        return link(bytes);
    }

    private static String v05Link(byte[]... ips) {
        byte[] bytes = new byte[37 + 4 * ips.length];
        bytes[0] = 0x05;
        bytes[1] = 0x01;
        bytes[2] = (byte) ips.length;
        bytes[3] = 0x1d;
        bytes[4] = (byte) 0xe9;
        for (int index = 0; index < ips.length; index++) {
            System.arraycopy(ips[index], 0, bytes, 5 + 4 * index, 4);
        }
        return link(bytes);
    }

    private static String link(byte[] bytes) {
        return "https://go.solstone.app/p#" + encodeCrockford(bytes);
    }

    private static String encodeCrockford(byte[] bytes) {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        StringBuilder output = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte raw : bytes) {
            buffer = (buffer << 8) | (raw & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                output.append(alphabet.charAt((buffer >> bits) & 31));
                buffer &= (1 << bits) - 1;
            }
        }
        if (bits > 0) {
            output.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        }
        return output.toString();
    }
}
