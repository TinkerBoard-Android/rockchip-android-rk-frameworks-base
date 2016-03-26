/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.apf;

import static android.system.OsConstants.*;

import android.net.NetworkUtils;
import android.net.apf.ApfGenerator;
import android.net.apf.ApfGenerator.IllegalInstructionException;
import android.net.apf.ApfGenerator.Register;
import android.net.ip.IpManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.PacketSocketAddress;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.HexDump;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.Thread;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.util.ArrayList;
import java.util.Arrays;

import libcore.io.IoBridge;

/**
 * For networks that support packet filtering via APF programs, {@code ApfFilter}
 * listens for IPv6 ICMPv6 router advertisements (RAs) and generates APF programs to
 * filter out redundant duplicate ones.
 *
 * Threading model:
 * A collection of RAs we've received is kept in mRas. Generating APF programs uses mRas to
 * know what RAs to filter for, thus generating APF programs is dependent on mRas.
 * mRas can be accessed by multiple threads:
 * - ReceiveThread, which listens for RAs and adds them to mRas, and generates APF programs.
 * - callers of:
 *    - setMulticastFilter(), which can cause an APF program to be generated.
 *    - dump(), which dumps mRas among other things.
 *    - shutdown(), which clears mRas.
 * So access to mRas is synchronized.
 *
 * @hide
 */
public class ApfFilter {
    // Thread to listen for RAs.
    private class ReceiveThread extends Thread {
        private final byte[] mPacket = new byte[1514];
        private final FileDescriptor mSocket;
        private volatile boolean mStopped;

        public ReceiveThread(FileDescriptor socket) {
            mSocket = socket;
        }

        public void halt() {
            mStopped = true;
            try {
                // Interrupts the read() call the thread is blocked in.
                IoBridge.closeAndSignalBlockedThreads(mSocket);
            } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            log("begin monitoring");
            while (!mStopped) {
                try {
                    int length = Os.read(mSocket, mPacket, 0, mPacket.length);
                    processRa(mPacket, length);
                } catch (IOException|ErrnoException e) {
                    if (!mStopped) {
                        Log.e(TAG, "Read error", e);
                    }
                }
            }
        }
    }

    private static final String TAG = "ApfFilter";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int ETH_HEADER_LEN = 14;
    private static final int ETH_ETHERTYPE_OFFSET = 12;
    private static final byte[] ETH_BROADCAST_MAC_ADDRESS = new byte[]{
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff };
    // TODO: Make these offsets relative to end of link-layer header; don't include ETH_HEADER_LEN.
    private static final int IPV4_FRAGMENT_OFFSET_OFFSET = ETH_HEADER_LEN + 6;
    // Endianness is not an issue for this constant because the APF interpreter always operates in
    // network byte order.
    private static final int IPV4_FRAGMENT_OFFSET_MASK = 0x1fff;
    private static final int IPV4_PROTOCOL_OFFSET = ETH_HEADER_LEN + 9;
    private static final int IPV4_DEST_ADDR_OFFSET = ETH_HEADER_LEN + 16;

    private static final int IPV6_NEXT_HEADER_OFFSET = ETH_HEADER_LEN + 6;
    private static final int IPV6_SRC_ADDR_OFFSET = ETH_HEADER_LEN + 8;
    private static final int IPV6_DEST_ADDR_OFFSET = ETH_HEADER_LEN + 24;
    private static final int IPV6_HEADER_LEN = 40;
    // The IPv6 all nodes address ff02::1
    private static final byte[] IPV6_ALL_NODES_ADDRESS =
            new byte[]{ (byte) 0xff, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 };

    private static final int ICMP6_TYPE_OFFSET = ETH_HEADER_LEN + IPV6_HEADER_LEN;
    private static final int ICMP6_NEIGHBOR_ANNOUNCEMENT = 136;

    // NOTE: this must be added to the IPv4 header length in IPV4_HEADER_SIZE_MEMORY_SLOT
    private static final int UDP_DESTINATION_PORT_OFFSET = ETH_HEADER_LEN + 2;
    private static final int UDP_HEADER_LEN = 8;

    private static final int DHCP_CLIENT_PORT = 68;
    // NOTE: this must be added to the IPv4 header length in IPV4_HEADER_SIZE_MEMORY_SLOT
    private static final int DHCP_CLIENT_MAC_OFFSET = ETH_HEADER_LEN + UDP_HEADER_LEN + 28;

    private final ApfCapabilities mApfCapabilities;
    private final IpManager.Callback mIpManagerCallback;
    private final NetworkInterface mNetworkInterface;
    private byte[] mHardwareAddress;
    private ReceiveThread mReceiveThread;
    @GuardedBy("this")
    private long mUniqueCounter;
    @GuardedBy("this")
    private boolean mMulticastFilter;

    private ApfFilter(ApfCapabilities apfCapabilities, NetworkInterface networkInterface,
            IpManager.Callback ipManagerCallback) {
        mApfCapabilities = apfCapabilities;
        mIpManagerCallback = ipManagerCallback;
        mNetworkInterface = networkInterface;

        maybeStartFilter();
    }

    private void log(String s) {
        Log.d(TAG, "(" + mNetworkInterface.getName() + "): " + s);
    }

    @GuardedBy("this")
    private long getUniqueNumberLocked() {
        return mUniqueCounter++;
    }

    /**
     * Attempt to start listening for RAs and, if RAs are received, generating and installing
     * filters to ignore useless RAs.
     */
    private void maybeStartFilter() {
        FileDescriptor socket;
        try {
            mHardwareAddress = mNetworkInterface.getHardwareAddress();
            synchronized(this) {
                // Install basic filters
                installNewProgramLocked();
            }
            socket = Os.socket(AF_PACKET, SOCK_RAW, ETH_P_IPV6);
            PacketSocketAddress addr = new PacketSocketAddress((short) ETH_P_IPV6,
                    mNetworkInterface.getIndex());
            Os.bind(socket, addr);
            NetworkUtils.attachRaFilter(socket, mApfCapabilities.apfPacketFormat);
        } catch(SocketException|ErrnoException e) {
            Log.e(TAG, "Error starting filter", e);
            return;
        }
        mReceiveThread = new ReceiveThread(socket);
        mReceiveThread.start();
    }

    // Returns seconds since Unix Epoch.
    private static long curTime() {
        return System.currentTimeMillis() / 1000L;
    }

    // A class to hold information about an RA.
    private class Ra {
        // From RFC4861:
        private static final int ICMP6_RA_HEADER_LEN = 16;
        private static final int ICMP6_RA_CHECKSUM_OFFSET =
                ETH_HEADER_LEN + IPV6_HEADER_LEN + 2;
        private static final int ICMP6_RA_CHECKSUM_LEN = 2;
        private static final int ICMP6_RA_OPTION_OFFSET =
                ETH_HEADER_LEN + IPV6_HEADER_LEN + ICMP6_RA_HEADER_LEN;
        private static final int ICMP6_RA_ROUTER_LIFETIME_OFFSET =
                ETH_HEADER_LEN + IPV6_HEADER_LEN + 6;
        private static final int ICMP6_RA_ROUTER_LIFETIME_LEN = 2;
        // Prefix information option.
        private static final int ICMP6_PREFIX_OPTION_TYPE = 3;
        private static final int ICMP6_PREFIX_OPTION_LEN = 32;
        private static final int ICMP6_PREFIX_OPTION_VALID_LIFETIME_OFFSET = 4;
        private static final int ICMP6_PREFIX_OPTION_VALID_LIFETIME_LEN = 4;
        private static final int ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_OFFSET = 8;
        private static final int ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_LEN = 4;

        // From RFC6106: Recursive DNS Server option
        private static final int ICMP6_RDNSS_OPTION_TYPE = 25;
        // From RFC6106: DNS Search List option
        private static final int ICMP6_DNSSL_OPTION_TYPE = 31;

        // From RFC4191: Route Information option
        private static final int ICMP6_ROUTE_INFO_OPTION_TYPE = 24;
        // Above three options all have the same format:
        private static final int ICMP6_4_BYTE_LIFETIME_OFFSET = 4;
        private static final int ICMP6_4_BYTE_LIFETIME_LEN = 4;

        private final ByteBuffer mPacket;
        // List of binary ranges that include the whole packet except the lifetimes.
        // Pairs consist of offset and length.
        private final ArrayList<Pair<Integer, Integer>> mNonLifetimes =
                new ArrayList<Pair<Integer, Integer>>();
        // Minimum lifetime in packet
        long mMinLifetime;
        // When the packet was last captured, in seconds since Unix Epoch
        long mLastSeen;

        // For debugging only. Offsets into the packet where PIOs are.
        private final ArrayList<Integer> mPrefixOptionOffsets;
        // For debugging only. How many times this RA was seen.
        int seenCount = 0;

        // For debugging only. Returns the hex representation of the last matching packet.
        String getLastMatchingPacket() {
            return HexDump.toHexString(mPacket.array(), 0, mPacket.capacity(), false /* lowercase */);
        }

        private String IPv6AddresstoString(int pos) {
            try {
                byte[] array = mPacket.array();
                // Can't just call copyOfRange() and see if it throws, because if it reads past the
                // end it pads with zeros instead of throwing.
                if (pos < 0 || pos + 16 > array.length || pos + 16 < pos) {
                    return "???";
                }
                byte[] addressBytes = Arrays.copyOfRange(array, pos, pos + 16);
                InetAddress address = (Inet6Address) InetAddress.getByAddress(addressBytes);
                return address.getHostAddress();
            } catch (UnsupportedOperationException e) {
                // array() failed. Cannot happen, mPacket is array-backed and read-write.
                return "???";
            } catch (ClassCastException | UnknownHostException e) {
                // Cannot happen.
                return "???";
            }
        }

        // Can't be static because it's in a non-static inner class.
        // TODO: Make this final once RA is its own class.
        private int uint8(byte b) {
            return b & 0xff;
        }

        private int uint16(short s) {
            return s & 0xffff;
        }

        private long uint32(int s) {
            return s & 0xffffffff;
        }

        public String toString() {
            try {
                StringBuffer sb = new StringBuffer();
                sb.append(String.format("RA %s -> %s %d ",
                        IPv6AddresstoString(IPV6_SRC_ADDR_OFFSET),
                        IPv6AddresstoString(IPV6_DEST_ADDR_OFFSET),
                        uint16(mPacket.getShort(ICMP6_RA_ROUTER_LIFETIME_OFFSET))));
                for (int i: mPrefixOptionOffsets) {
                    String prefix = IPv6AddresstoString(i + 16);
                    int length = uint8(mPacket.get(i + 2));
                    long valid = mPacket.getInt(i + 4);
                    long preferred = mPacket.getInt(i + 8);
                    sb.append(String.format("%s/%d %d/%d ", prefix, length, valid, preferred));
                }
                return sb.toString();
            } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
                return "<Malformed RA>";
            }
        }

        /**
         * Add a binary range of the packet that does not include a lifetime to mNonLifetimes.
         * Assumes mPacket.position() is as far as we've parsed the packet.
         * @param lastNonLifetimeStart offset within packet of where the last binary range of
         *                             data not including a lifetime.
         * @param lifetimeOffset offset from mPacket.position() to the next lifetime data.
         * @param lifetimeLength length of the next lifetime data.
         * @return offset within packet of where the next binary range of data not including
         *         a lifetime.  This can be passed into the next invocation of this function
         *         via {@code lastNonLifetimeStart}.
         */
        private int addNonLifetime(int lastNonLifetimeStart, int lifetimeOffset,
                int lifetimeLength) {
            lifetimeOffset += mPacket.position();
            mNonLifetimes.add(new Pair<Integer, Integer>(lastNonLifetimeStart,
                    lifetimeOffset - lastNonLifetimeStart));
            return lifetimeOffset + lifetimeLength;
        }

        // Note that this parses RA and may throw IllegalArgumentException (from
        // Buffer.position(int) ) or IndexOutOfBoundsException (from ByteBuffer.get(int) ) if
        // parsing encounters something non-compliant with specifications.
        Ra(byte[] packet, int length) {
            mPacket = ByteBuffer.allocate(length).put(ByteBuffer.wrap(packet, 0, length));
            mPacket.clear();
            mLastSeen = curTime();

            // Ignore the checksum.
            int lastNonLifetimeStart = addNonLifetime(0,
                    ICMP6_RA_CHECKSUM_OFFSET,
                    ICMP6_RA_CHECKSUM_LEN);

            // Parse router lifetime
            lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                    ICMP6_RA_ROUTER_LIFETIME_OFFSET,
                    ICMP6_RA_ROUTER_LIFETIME_LEN);

            // Parse ICMPv6 options
            mPrefixOptionOffsets = new ArrayList<>();
            mPacket.position(ICMP6_RA_OPTION_OFFSET);
            while (mPacket.hasRemaining()) {
                int optionType = ((int)mPacket.get(mPacket.position())) & 0xff;
                int optionLength = (((int)mPacket.get(mPacket.position() + 1)) & 0xff) * 8;
                switch (optionType) {
                    case ICMP6_PREFIX_OPTION_TYPE:
                        // Parse valid lifetime
                        lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                                ICMP6_PREFIX_OPTION_VALID_LIFETIME_OFFSET,
                                ICMP6_PREFIX_OPTION_VALID_LIFETIME_LEN);
                        // Parse preferred lifetime
                        lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                                ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_OFFSET,
                                ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_LEN);
                        mPrefixOptionOffsets.add(mPacket.position());
                        break;
                    // These three options have the same lifetime offset and size, so process
                    // together:
                    case ICMP6_ROUTE_INFO_OPTION_TYPE:
                    case ICMP6_RDNSS_OPTION_TYPE:
                    case ICMP6_DNSSL_OPTION_TYPE:
                        // Parse lifetime
                        lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                                ICMP6_4_BYTE_LIFETIME_OFFSET,
                                ICMP6_4_BYTE_LIFETIME_LEN);
                        break;
                    default:
                        // RFC4861 section 4.2 dictates we ignore unknown options for fowards
                        // compatibility.
                        break;
                }
                mPacket.position(mPacket.position() + optionLength);
            }
            // Mark non-lifetime bytes since last lifetime.
            addNonLifetime(lastNonLifetimeStart, 0, 0);
            mMinLifetime = minLifetime(packet, length);
        }

        // Ignoring lifetimes (which may change) does {@code packet} match this RA?
        boolean matches(byte[] packet, int length) {
            if (length != mPacket.limit()) return false;
            ByteBuffer a = ByteBuffer.wrap(packet);
            ByteBuffer b = mPacket;
            for (Pair<Integer, Integer> nonLifetime : mNonLifetimes) {
                a.clear();
                b.clear();
                a.position(nonLifetime.first);
                b.position(nonLifetime.first);
                a.limit(nonLifetime.first + nonLifetime.second);
                b.limit(nonLifetime.first + nonLifetime.second);
                if (a.compareTo(b) != 0) return false;
            }
            return true;
        }

        // What is the minimum of all lifetimes within {@code packet} in seconds?
        // Precondition: matches(packet, length) already returned true.
        long minLifetime(byte[] packet, int length) {
            long minLifetime = Long.MAX_VALUE;
            // Wrap packet in ByteBuffer so we can read big-endian values easily
            ByteBuffer byteBuffer = ByteBuffer.wrap(packet);
            for (int i = 0; (i + 1) < mNonLifetimes.size(); i++) {
                int offset = mNonLifetimes.get(i).first + mNonLifetimes.get(i).second;

                // The checksum is in mNonLifetimes, but it's not a lifetime.
                if (offset == ICMP6_RA_CHECKSUM_OFFSET) {
                     continue;
                }

                int lifetimeLength = mNonLifetimes.get(i+1).first - offset;
                long val;
                switch (lifetimeLength) {
                    case 2: val = byteBuffer.getShort(offset); break;
                    case 4: val = byteBuffer.getInt(offset); break;
                    default: throw new IllegalStateException("bogus lifetime size " + length);
                }
                // Mask to size, converting signed to unsigned
                val &= (1L << (lifetimeLength * 8)) - 1;
                minLifetime = Math.min(minLifetime, val);
            }
            return minLifetime;
        }

        // How many seconds does this RA's have to live, taking into account the fact
        // that we might have seen it a while ago.
        long currentLifetime() {
            return mMinLifetime - (curTime() - mLastSeen);
        }

        boolean isExpired() {
            // TODO: We may want to handle 0 lifetime RAs differently, if they are common. We'll
            // have to calculte the filter lifetime specially as a fraction of 0 is still 0.
            return currentLifetime() <= 0;
        }

        // Append a filter for this RA to {@code gen}. Jump to DROP_LABEL if it should be dropped.
        // Jump to the next filter if packet doesn't match this RA.
        @GuardedBy("ApfFilter.this")
        long generateFilterLocked(ApfGenerator gen) throws IllegalInstructionException {
            String nextFilterLabel = "Ra" + getUniqueNumberLocked();
            // Skip if packet is not the right size
            gen.addLoadFromMemory(Register.R0, gen.PACKET_SIZE_MEMORY_SLOT);
            gen.addJumpIfR0NotEquals(mPacket.limit(), nextFilterLabel);
            int filterLifetime = (int)(currentLifetime() / FRACTION_OF_LIFETIME_TO_FILTER);
            // Skip filter if expired
            gen.addLoadFromMemory(Register.R0, gen.FILTER_AGE_MEMORY_SLOT);
            gen.addJumpIfR0GreaterThan(filterLifetime, nextFilterLabel);
            for (int i = 0; i < mNonLifetimes.size(); i++) {
                // Generate code to match the packet bytes
                Pair<Integer, Integer> nonLifetime = mNonLifetimes.get(i);
                // Don't generate JNEBS instruction for 0 bytes as it always fails the
                // ASSERT_FORWARD_IN_PROGRAM(pc + cmp_imm - 1) check where cmp_imm is
                // the number of bytes to compare. nonLifetime is zero between the
                // valid and preferred lifetimes in the prefix option.
                if (nonLifetime.second != 0) {
                    gen.addLoadImmediate(Register.R0, nonLifetime.first);
                    gen.addJumpIfBytesNotEqual(Register.R0,
                            Arrays.copyOfRange(mPacket.array(), nonLifetime.first,
                                               nonLifetime.first + nonLifetime.second),
                            nextFilterLabel);
                }
                // Generate code to test the lifetimes haven't gone down too far
                if ((i + 1) < mNonLifetimes.size()) {
                    Pair<Integer, Integer> nextNonLifetime = mNonLifetimes.get(i + 1);
                    int offset = nonLifetime.first + nonLifetime.second;
                    // Skip the checksum.
                    if (offset == ICMP6_RA_CHECKSUM_OFFSET) {
                        continue;
                    }
                    int length = nextNonLifetime.first - offset;
                    switch (length) {
                        case 4: gen.addLoad32(Register.R0, offset); break;
                        case 2: gen.addLoad16(Register.R0, offset); break;
                        default: throw new IllegalStateException("bogus lifetime size " + length);
                    }
                    gen.addJumpIfR0LessThan(filterLifetime, nextFilterLabel);
                }
            }
            gen.addJump(gen.DROP_LABEL);
            gen.defineLabel(nextFilterLabel);
            return filterLifetime;
        }
    }

    // Maximum number of RAs to filter for.
    private static final int MAX_RAS = 10;

    @GuardedBy("this")
    private ArrayList<Ra> mRas = new ArrayList<Ra>();

    // There is always some marginal benefit to updating the installed APF program when an RA is
    // seen because we can extend the program's lifetime slightly, but there is some cost to
    // updating the program, so don't bother unless the program is going to expire soon. This
    // constant defines "soon" in seconds.
    private static final long MAX_PROGRAM_LIFETIME_WORTH_REFRESHING = 30;
    // We don't want to filter an RA for it's whole lifetime as it'll be expired by the time we ever
    // see a refresh.  Using half the lifetime might be a good idea except for the fact that
    // packets may be dropped, so let's use 6.
    private static final int FRACTION_OF_LIFETIME_TO_FILTER = 6;

    // When did we last install a filter program? In seconds since Unix Epoch.
    @GuardedBy("this")
    private long mLastTimeInstalledProgram;
    // How long should the last installed filter program live for? In seconds.
    @GuardedBy("this")
    private long mLastInstalledProgramMinLifetime;

    // For debugging only. The last program installed.
    @GuardedBy("this")
    private byte[] mLastInstalledProgram;

    /**
     * Generate filter code to process IPv4 packets. Execution of this code ends in either the
     * DROP_LABEL or PASS_LABEL and does not fall off the end.
     * Preconditions:
     *  - Packet being filtered is IPv4
     *  - R1 is initialized to 0
     */
    @GuardedBy("this")
    private void generateIPv4FilterLocked(ApfGenerator gen) throws IllegalInstructionException {
        // Here's a basic summary of what the IPv4 filter program does:
        //
        // if it's multicast and we're dropping multicast:
        //   drop
        // if it's not broadcast:
        //   pass
        // if it's not DHCP destined to our MAC:
        //   drop
        // pass

        if (mMulticastFilter) {
            // Check for multicast destination address range
            gen.addLoad8(Register.R0, IPV4_DEST_ADDR_OFFSET);
            gen.addAnd(0xf0);
            gen.addJumpIfR0Equals(0xe0, gen.DROP_LABEL);
        }

        // Drop all broadcasts besides DHCP addressed to us
        // If not a broadcast packet, pass
        // NOTE: Relies on R1 being initialized to 0 which is the offset of the ethernet
        //       destination MAC address
        gen.addJumpIfBytesNotEqual(Register.R1, ETH_BROADCAST_MAC_ADDRESS, gen.PASS_LABEL);
        // If not UDP, drop
        gen.addLoad8(Register.R0, IPV4_PROTOCOL_OFFSET);
        gen.addJumpIfR0NotEquals(IPPROTO_UDP, gen.DROP_LABEL);
        // If fragment, drop. This matches the BPF filter installed by the DHCP client.
        gen.addLoad16(Register.R0, IPV4_FRAGMENT_OFFSET_OFFSET);
        gen.addJumpIfR0AnyBitsSet(IPV4_FRAGMENT_OFFSET_MASK, gen.DROP_LABEL);
        // If not to DHCP client port, drop
        gen.addLoadFromMemory(Register.R1, gen.IPV4_HEADER_SIZE_MEMORY_SLOT);
        gen.addLoad16Indexed(Register.R0, UDP_DESTINATION_PORT_OFFSET);
        gen.addJumpIfR0NotEquals(DHCP_CLIENT_PORT, gen.DROP_LABEL);
        // If not DHCP to our MAC address, drop
        gen.addLoadImmediate(Register.R0, DHCP_CLIENT_MAC_OFFSET);
        // NOTE: Relies on R1 containing IPv4 header offset.
        gen.addAddR1();
        gen.addJumpIfBytesNotEqual(Register.R0, mHardwareAddress, gen.DROP_LABEL);

        // Otherwise, pass
        gen.addJump(gen.PASS_LABEL);
    }


    /**
     * Generate filter code to process IPv6 packets. Execution of this code ends in either the
     * DROP_LABEL or PASS_LABEL, or falls off the end for ICMPv6 packets.
     * Preconditions:
     *  - Packet being filtered is IPv6
     *  - R1 is initialized to 0
     */
    @GuardedBy("this")
    private void generateIPv6FilterLocked(ApfGenerator gen) throws IllegalInstructionException {
        // Here's a basic summary of what the IPv6 filter program does:
        //
        // if it's not ICMPv6:
        //   pass
        // if it's ICMPv6 NA to ff02::1:
        //   drop

        // If not ICMPv6, pass
        gen.addLoad8(Register.R0, IPV6_NEXT_HEADER_OFFSET);
        // TODO: Drop multicast if the multicast filter is enabled.
        gen.addJumpIfR0NotEquals(IPPROTO_ICMPV6, gen.PASS_LABEL);
        // Add unsolicited multicast neighbor announcements filter
        String skipUnsolicitedMulticastNALabel = "skipUnsolicitedMulticastNA";
        // If not neighbor announcements, skip unsolicited multicast NA filter
        gen.addLoad8(Register.R0, ICMP6_TYPE_OFFSET);
        gen.addJumpIfR0NotEquals(ICMP6_NEIGHBOR_ANNOUNCEMENT, skipUnsolicitedMulticastNALabel);
        // If to ff02::1, drop
        // TODO: Drop only if they don't contain the address of on-link neighbours.
        gen.addLoadImmediate(Register.R0, IPV6_DEST_ADDR_OFFSET);
        gen.addJumpIfBytesNotEqual(Register.R0, IPV6_ALL_NODES_ADDRESS,
                skipUnsolicitedMulticastNALabel);
        gen.addJump(gen.DROP_LABEL);
        gen.defineLabel(skipUnsolicitedMulticastNALabel);
    }

    /**
     * Begin generating an APF program to:
     * <ul>
     * <li>Drop IPv4 broadcast packets, except DHCP destined to our MAC,
     * <li>Drop IPv4 multicast packets, if mMulticastFilter,
     * <li>Pass all other IPv4 packets,
     * <li>Pass all non-ICMPv6 IPv6 packets,
     * <li>Pass all non-IPv4 and non-IPv6 packets,
     * <li>Drop IPv6 ICMPv6 NAs to ff02::1.
     * <li>Let execution continue off the end of the program for IPv6 ICMPv6 packets. This allows
     *     insertion of RA filters here, or if there aren't any, just passes the packets.
     * </ul>
     */
    @GuardedBy("this")
    private ApfGenerator beginProgramLocked() throws IllegalInstructionException {
        ApfGenerator gen = new ApfGenerator();
        // This is guaranteed to return true because of the check in maybeCreate.
        gen.setApfVersion(mApfCapabilities.apfVersionSupported);

        // Here's a basic summary of what the initial program does:
        //
        // if it's IPv4:
        //   insert IPv4 filter to drop or pass these appropriately
        // if it's not IPv6:
        //   pass
        // insert IPv6 filter to drop, pass, or fall off the end for ICMPv6 packets

        // Add IPv4 filters:
        String skipIPv4FiltersLabel = "skipIPv4Filters";
        // If not IPv4, skip IPv4 filters
        gen.addLoad16(Register.R0, ETH_ETHERTYPE_OFFSET);
        gen.addJumpIfR0NotEquals(ETH_P_IP, skipIPv4FiltersLabel);
        // NOTE: Relies on R1 being initialized to 0.
        generateIPv4FilterLocked(gen);
        gen.defineLabel(skipIPv4FiltersLabel);

        // Add IPv6 filters:
        // If not IPv6, pass
        // NOTE: Relies on R0 containing ethertype. This is safe because if we got here, we did not
        // execute the IPv4 filter, since that filter does not fall through, but either drops or
        // passes.
        gen.addJumpIfR0NotEquals(ETH_P_IPV6, gen.PASS_LABEL);
        generateIPv6FilterLocked(gen);
        return gen;
    }

    @GuardedBy("this")
    private void installNewProgramLocked() {
        purgeExpiredRasLocked();
        final byte[] program;
        long programMinLifetime = Long.MAX_VALUE;
        try {
            // Step 1: Determine how many RA filters we can fit in the program.
            ApfGenerator gen = beginProgramLocked();
            ArrayList<Ra> rasToFilter = new ArrayList<Ra>();
            for (Ra ra : mRas) {
                ra.generateFilterLocked(gen);
                // Stop if we get too big.
                if (gen.programLengthOverEstimate() > mApfCapabilities.maximumApfProgramSize) break;
                rasToFilter.add(ra);
            }
            // Step 2: Actually generate the program
            gen = beginProgramLocked();
            for (Ra ra : rasToFilter) {
                programMinLifetime = Math.min(programMinLifetime, ra.generateFilterLocked(gen));
            }
            // Execution will reach the end of the program if no filters match, which will pass the
            // packet to the AP.
            program = gen.generate();
        } catch (IllegalInstructionException e) {
            Log.e(TAG, "Program failed to generate: ", e);
            return;
        }
        mLastTimeInstalledProgram = curTime();
        mLastInstalledProgramMinLifetime = programMinLifetime;
        mLastInstalledProgram = program;
        if (VDBG) {
            hexDump("Installing filter: ", program, program.length);
        }
        mIpManagerCallback.installPacketFilter(program);
    }

    // Install a new filter program if the last installed one will die soon.
    @GuardedBy("this")
    private void maybeInstallNewProgramLocked() {
        if (mRas.size() == 0) return;
        // If the current program doesn't expire for a while, don't bother updating.
        long expiry = mLastTimeInstalledProgram + mLastInstalledProgramMinLifetime;
        if (expiry < curTime() + MAX_PROGRAM_LIFETIME_WORTH_REFRESHING) {
            installNewProgramLocked();
        }
    }

    private void hexDump(String msg, byte[] packet, int length) {
        log(msg + HexDump.toHexString(packet, 0, length, false /* lowercase */));
    }

    @GuardedBy("this")
    private void purgeExpiredRasLocked() {
        for (int i = 0; i < mRas.size();) {
            if (mRas.get(i).isExpired()) {
                log("Expiring " + mRas.get(i));
                mRas.remove(i);
            } else {
                i++;
            }
        }
    }

    private synchronized void processRa(byte[] packet, int length) {
        if (VDBG) hexDump("Read packet = ", packet, length);

        // Have we seen this RA before?
        for (int i = 0; i < mRas.size(); i++) {
            Ra ra = mRas.get(i);
            if (ra.matches(packet, length)) {
                if (VDBG) log("matched RA " + ra);
                // Update lifetimes.
                ra.mLastSeen = curTime();
                ra.mMinLifetime = ra.minLifetime(packet, length);
                ra.seenCount++;

                // Keep mRas in LRU order so as to prioritize generating filters for recently seen
                // RAs. LRU prioritizes this because RA filters are generated in order from mRas
                // until the filter program exceeds the maximum filter program size allowed by the
                // chipset, so RAs appearing earlier in mRas are more likely to make it into the
                // filter program.
                // TODO: consider sorting the RAs in order of increasing expiry time as well.
                // Swap to front of array.
                mRas.add(0, mRas.remove(i));

                maybeInstallNewProgramLocked();
                return;
            }
        }
        purgeExpiredRasLocked();
        // TODO: figure out how to proceed when we've received more then MAX_RAS RAs.
        if (mRas.size() >= MAX_RAS) return;
        final Ra ra;
        try {
            ra = new Ra(packet, length);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing RA: " + e);
            return;
        }
        // Ignore 0 lifetime RAs.
        if (ra.isExpired()) return;
        log("Adding " + ra);
        mRas.add(ra);
        installNewProgramLocked();
    }

    /**
     * Create an {@link ApfFilter} if {@code apfCapabilities} indicates support for packet
     * filtering using APF programs.
     */
    public static ApfFilter maybeCreate(ApfCapabilities apfCapabilities,
            NetworkInterface networkInterface, IpManager.Callback ipManagerCallback) {
        if (apfCapabilities == null || networkInterface == null) return null;
        if (apfCapabilities.apfVersionSupported == 0) return null;
        if (apfCapabilities.maximumApfProgramSize < 512) {
            Log.e(TAG, "Unacceptably small APF limit: " + apfCapabilities.maximumApfProgramSize);
            return null;
        }
        // For now only support generating programs for Ethernet frames. If this restriction is
        // lifted:
        //   1. the program generator will need its offsets adjusted.
        //   2. the packet filter attached to our packet socket will need its offset adjusted.
        if (apfCapabilities.apfPacketFormat != ARPHRD_ETHER) return null;
        if (!new ApfGenerator().setApfVersion(apfCapabilities.apfVersionSupported)) {
            Log.e(TAG, "Unsupported APF version: " + apfCapabilities.apfVersionSupported);
            return null;
        }
        return new ApfFilter(apfCapabilities, networkInterface, ipManagerCallback);
    }

    public synchronized void shutdown() {
        if (mReceiveThread != null) {
            log("shutting down");
            mReceiveThread.halt();  // Also closes socket.
            mReceiveThread = null;
        }
        mRas.clear();
    }

    public synchronized void setMulticastFilter(boolean enabled) {
        if (mMulticastFilter != enabled) {
            mMulticastFilter = enabled;
            installNewProgramLocked();
        }
    }

    public synchronized void dump(IndentingPrintWriter pw) {
        pw.println("APF version: " + mApfCapabilities.apfVersionSupported);
        pw.println("Max program size: " + mApfCapabilities.maximumApfProgramSize);
        pw.println("Receive thread: " + (mReceiveThread != null ? "RUNNING" : "STOPPED"));
        if (mLastTimeInstalledProgram == 0) {
            pw.println("No program installed.");
            return;
        }

        pw.println(String.format(
                "Last program length %d, installed %ds ago, lifetime %d",
                mLastInstalledProgram.length, curTime() - mLastTimeInstalledProgram,
                mLastInstalledProgramMinLifetime));

        pw.println("RA filters:");
        pw.increaseIndent();
        for (Ra ra: mRas) {
            pw.println(ra);
            pw.increaseIndent();
            pw.println(String.format(
                    "Seen: %d, last %ds ago", ra.seenCount, curTime() - ra.mLastSeen));
            if (DBG) {
                pw.println("Last match:");
                pw.increaseIndent();
                pw.println(ra.getLastMatchingPacket());
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
        }

        if (DBG) {
            pw.println("Last program:");
            pw.increaseIndent();
            pw.println(HexDump.toHexString(mLastInstalledProgram, false /* lowercase */));
            pw.decreaseIndent();
        }

        pw.decreaseIndent();
    }
}