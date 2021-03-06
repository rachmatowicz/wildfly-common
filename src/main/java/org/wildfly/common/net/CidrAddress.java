/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.common.net;

import java.io.Serializable;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import org.wildfly.common.Assert;
import org.wildfly.common._private.CommonMessages;
import org.wildfly.common.math.HashMath;

/**
 * A Classless Inter-Domain Routing address.  This is the combination of an IP address and a netmask.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class CidrAddress implements Serializable {
    private static final long serialVersionUID = - 6548529324373774149L;

    /**
     * The CIDR address representing all IPv4 addresses.
     */
    public static final CidrAddress INET4_ANY_CIDR = new CidrAddress(Inet.INET4_ANY, 0);

    /**
     * The CIDR address representing all IPv6 addresses.
     */
    public static final CidrAddress INET6_ANY_CIDR = new CidrAddress(Inet.INET6_ANY, 0);

    private final InetAddress networkAddress;
    private final byte[] cachedBytes;
    private final int netmaskBits;
    private Inet4Address broadcast;
    private String toString;
    private int hashCode;

    CidrAddress(final InetAddress networkAddress, final int netmaskBits) {
        this.networkAddress = networkAddress;
        cachedBytes = networkAddress.getAddress();
        this.netmaskBits = netmaskBits;
    }

    /**
     * Create a new CIDR address.
     *
     * @param networkAddress the network address (must not be {@code null})
     * @param netmaskBits the netmask bits (0-32 for IPv4, or 0-128 for IPv6)
     * @return the CIDR address (not {@code null})
     */
    public static CidrAddress create(InetAddress networkAddress, int netmaskBits) {
        Assert.checkNotNullParam("networkAddress", networkAddress);
        Assert.checkMinimumParameter("netmaskBits", 0, netmaskBits);
        if (networkAddress instanceof Inet4Address) {
            Assert.checkMaximumParameter("netmaskBits", 32, netmaskBits);
            if (netmaskBits == 0) {
                return INET4_ANY_CIDR;
            }
        } else if (networkAddress instanceof Inet6Address) {
            Assert.checkMaximumParameter("netmaskBits", 128, netmaskBits);
            if (netmaskBits == 0) {
                return INET6_ANY_CIDR;
            }
        } else {
            throw Assert.unreachableCode();
        }
        final byte[] bytes = networkAddress.getAddress();
        maskBits0(bytes, netmaskBits);
        String name = Inet.toOptimalString(bytes);
        try {
            return new CidrAddress(InetAddress.getByAddress(name, bytes), netmaskBits);
        } catch (UnknownHostException e) {
            throw Assert.unreachableCode();
        }
    }

    /**
     * Create a new CIDR address.
     *
     * @param addressBytes the network address bytes (must not be {@code null}, must be 4 bytes for IPv4 or 16 bytes for IPv6)
     * @param netmaskBits the netmask bits (0-32 for IPv4, or 0-128 for IPv6)
     * @return the CIDR address (not {@code null})
     */
    public static CidrAddress create(byte[] addressBytes, int netmaskBits) {
        return create(addressBytes, netmaskBits, true);
    }

    static CidrAddress create(byte[] addressBytes, int netmaskBits, boolean clone) {
        Assert.checkNotNullParam("networkAddress", addressBytes);
        Assert.checkMinimumParameter("netmaskBits", 0, netmaskBits);
        final int length = addressBytes.length;
        if (length == 4) {
            Assert.checkMaximumParameter("netmaskBits", 32, netmaskBits);
            if (netmaskBits == 0) {
                return INET4_ANY_CIDR;
            }
        } else if (length == 16) {
            Assert.checkMaximumParameter("netmaskBits", 128, netmaskBits);
            if (netmaskBits == 0) {
                return INET6_ANY_CIDR;
            }
        } else {
            throw CommonMessages.msg.invalidAddressBytes(length);
        }
        final byte[] bytes = clone ? addressBytes.clone() : addressBytes;
        maskBits0(bytes, netmaskBits);
        String name = Inet.toOptimalString(bytes);
        try {
            return new CidrAddress(InetAddress.getByAddress(name, bytes), netmaskBits);
        } catch (UnknownHostException e) {
            throw Assert.unreachableCode();
        }
    }

    /**
     * Determine if the given address matches this CIDR address.
     *
     * @param address the address to test
     * @return {@code true} if the address matches, {@code false} otherwise
     */
    public boolean isMatchedBy(InetAddress address) {
        Assert.checkNotNullParam("address", address);
        if (address instanceof Inet4Address) {
            return isMatchedBy((Inet4Address) address);
        } else if (address instanceof Inet6Address) {
            return isMatchedBy((Inet6Address) address);
        } else {
            throw Assert.unreachableCode();
        }
    }

    /**
     * Determine if the given address matches this CIDR address.
     *
     * @param address the address to test
     * @return {@code true} if the address matches, {@code false} otherwise
     */
    public boolean isMatchedBy(Inet4Address address) {
        Assert.checkNotNullParam("address", address);
        return networkAddress instanceof Inet4Address && bitsMatch(cachedBytes, address.getAddress(), netmaskBits);
    }

    /**
     * Determine if the given address matches this CIDR address.
     *
     * @param address the address to test
     * @return {@code true} if the address matches, {@code false} otherwise
     */
    public boolean isMatchedBy(Inet6Address address) {
        Assert.checkNotNullParam("address", address);
        return networkAddress instanceof Inet6Address && bitsMatch(cachedBytes, address.getAddress(), netmaskBits);
    }

    /**
     * Get the network address.  The returned address has a resolved name consisting of the most compact valid string
     * representation of the network of this CIDR address.
     *
     * @return the network address (not {@code null})
     */
    public InetAddress getNetworkAddress() {
        return networkAddress;
    }

    /**
     * Get the broadcast address for this CIDR block.  If the block has no broadcast address (either because it is IPv6
     * or it is too small) then {@code null} is returned.
     *
     * @return the broadcast address for this CIDR block, or {@code null} if there is none
     */
    public Inet4Address getBroadcastAddress() {
        final Inet4Address broadcast = this.broadcast;
        if (broadcast == null) {
            final int netmaskBits = this.netmaskBits;
            if (netmaskBits >= 31) {
                // definitely IPv6 or too small
                return null;
            }
            // still maybe IPv6
            final byte[] cachedBytes = this.cachedBytes;
            if (cachedBytes.length == 4) {
                // calculate
                if (netmaskBits == 0) {
                    return this.broadcast = Inet.INET4_BROADCAST;
                } else {
                    final byte[] bytes = maskBits1(cachedBytes.clone(), netmaskBits);
                    try {
                        return this.broadcast = (Inet4Address) InetAddress.getByAddress(Inet.toOptimalString(bytes), bytes);
                    } catch (UnknownHostException e) {
                        throw Assert.unreachableCode();
                    }
                }
            }
            return null;
        }
        return broadcast;
    }

    /**
     * Get the netmask bits.  This will be in the range 0-32 for IPv4 addresses, and 0-128 for IPv6 addresses.
     *
     * @return the netmask bits
     */
    public int getNetmaskBits() {
        return netmaskBits;
    }

    public boolean equals(final Object obj) {
        return obj instanceof CidrAddress && equals((CidrAddress) obj);
    }

    public boolean equals(final CidrAddress obj) {
        return obj == this || obj != null && netmaskBits == obj.netmaskBits && Arrays.equals(cachedBytes, obj.cachedBytes);
    }

    public int hashCode() {
        int hashCode = this.hashCode;
        if (hashCode == 0) {
            hashCode = HashMath.multiHashOrdered(netmaskBits, Arrays.hashCode(cachedBytes));
            if (hashCode == 0) {
                hashCode = 1;
            }
            this.hashCode = hashCode;
        }
        return hashCode;
    }

    public String toString() {
        final String toString = this.toString;
        if (toString == null) {
            return this.toString = String.format("%s/%d", Inet.toOptimalString(cachedBytes), Integer.valueOf(netmaskBits));
        }
        return toString;
    }

    Object writeReplace() {
        return new Ser(cachedBytes, netmaskBits);
    }

    static final class Ser implements Serializable {
        private static final long serialVersionUID = 6367919693596329038L;

        final byte[] b;
        final int m;

        Ser(final byte[] b, final int m) {
            this.b = b;
            this.m = m;
        }

        Object readResolve() {
            return create(b, m, false);
        }
    }


    private static boolean bitsMatch(byte[] address, byte[] test, int bits) {
        final int length = address.length;
        assert length == test.length;
        // bytes are in big-endian form.
        int i = 0;
        while (bits >= 8 && i < length) {
            if (address[i] != test[i]) {
                return false;
            }
            i ++;
            bits -= 8;
        }
        if (bits > 0) {
            assert bits < 8;
            int mask = 0xff << 8 - bits;
            if ((address[i] & 0xff & mask) != (test[i] & 0xff & mask)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] maskBits0(byte[] address, int bits) {
        final int length = address.length;
        // bytes are in big-endian form.
        int i = 0;
        while (bits >= 8 && i < length) {
            i ++;
            bits -= 8;
        }
        if (bits > 0) {
            assert bits < 8;
            int mask = 0xff << 8 - bits;
            address[i++] &= mask;
        }
        while (i < length) {
            address[i++] = 0;
        }
        return address;
    }

    private static byte[] maskBits1(byte[] address, int bits) {
        final int length = address.length;
        // bytes are in big-endian form.
        int i = 0;
        while (bits >= 8 && i < length) {
            i ++;
            bits -= 8;
        }
        if (bits > 0) {
            assert bits < 8;
            int mask = 0xff >>> 8 - bits;
            address[i++] |= mask;
        }
        while (i < length) {
            address[i++] = (byte) 0xff;
        }
        return address;
    }
}
