/*
 * Copied from Bouncy Castle for Java 1.87 (org.bouncycastle.math.ec.rfc7748), MIT licence:
 * third_party/licenses/BouncyCastle-MIT.txt. The Legion of the Bouncy Castle Inc.
 * (https://www.bouncycastle.org). PokeDaisy changes: the package; only calculateAgreement / generatePublicKey / scalarMult kept, the public key by scalarMult with u = 9 instead of Ed25519's base-point comb.
 */
package com.pokedaisy.app.companion.coop.x25519;

/** X25519 (RFC 7748): the two calls the co-op link makes. */
public abstract class X25519
{
    public static final int POINT_SIZE = 32;
    public static final int SCALAR_SIZE = 32;

    private static class F extends X25519Field {};

    private static final int C_A = 486662;
    private static final int C_A24 = (C_A + 2)/4;

    /** The shared secret in [r]; false when it is all zero (a low-order peer key). */
    public static boolean calculateAgreement(byte[] k, int kOff, byte[] u, int uOff, byte[] r, int rOff)
    {
        scalarMult(k, kOff, u, uOff, r, rOff);
        int bits = 0;
        for (int i = 0; i < POINT_SIZE; ++i)
        {
            bits |= r[rOff + i];
        }
        return bits != 0;
    }

    private static void decodeScalar(byte[] k, int kOff, int[] n)
    {
        for (int i = 0; i < 8; ++i)
        {
            n[i] = F.decode32(k, kOff + i * 4);
        }

        n[0] &= 0xFFFFFFF8;
        n[7] &= 0x7FFFFFFF;
        n[7] |= 0x40000000;
    }

    /** The public key of [k]: k times the base point u = 9 (what scalarMultBase computes, by the plain ladder). */
    public static void generatePublicKey(byte[] k, int kOff, byte[] r, int rOff)
    {
        byte[] u = new byte[POINT_SIZE];
        u[0] = 9;
        scalarMult(k, kOff, u, 0, r, rOff);
    }

    private static void pointDouble(int[] x, int[] z)
    {
        int[] a = F.create();
        int[] b = F.create();

        F.apm(x, z, a, b);
        F.sqr(a, a);
        F.sqr(b, b);
        F.mul(a, b, x);
        F.sub(a, b, a);
        F.mul(a, C_A24, z);
        F.add(z, b, z);
        F.mul(z, a, z);
    }

    public static void scalarMult(byte[] k, int kOff, byte[] u, int uOff, byte[] r, int rOff)
    {
        validate(k, kOff, SCALAR_SIZE);
        validate(u, uOff, POINT_SIZE);
        validate(r, rOff, POINT_SIZE);

        int[] n = new int[8];       decodeScalar(k, kOff, n);

        int[] x1 = F.create();      F.decode255(u, uOff, x1, 0);
        int[] x2 = F.create();      F.copy(x1, 0, x2, 0);
        int[] z2 = F.create();      z2[0] = 1;
        int[] x3 = F.create();      x3[0] = 1;
        int[] z3 = F.create();

        int[] t1 = F.create();
        int[] t2 = F.create();

//        assert n[7] >>> 30 == 1;

        int bit = 254, swap = 1;
        do
        {
            F.apm(x3, z3, t1, x3);
            F.apm(x2, z2, z3, x2);
            F.mul(t1, x2, t1);
            F.mul(x3, z3, x3);
            F.sqr(z3, z3);
            F.sqr(x2, x2);

            F.sub(z3, x2, t2);
            F.mul(t2, C_A24, z2);
            F.add(z2, x2, z2);
            F.mul(z2, t2, z2);
            F.mul(x2, z3, x2);

            F.apm(t1, x3, x3, z3);
            F.sqr(x3, x3);
            F.sqr(z3, z3);
            F.mul(z3, x1, z3);

            --bit;

            int word = bit >>> 5, shift = bit & 0x1F;
            int kt = (n[word] >>> shift) & 1;
            swap ^= kt;
            F.cswap(swap, x2, x3);
            F.cswap(swap, z2, z3);
            swap = kt;
        }
        while (bit >= 3);

//        assert swap == 0;

        for (int i = 0; i < 3; ++i)
        {
            pointDouble(x2, z2);
        }

        F.inv(z2, z2);
        F.mul(x2, z2, x2);

        F.normalize(x2);
        F.encode(x2, r, rOff);
    }

    private static void validate(byte[] buf, int off, int len)
    {
        if (buf == null) throw new NullPointerException("'buf' cannot be null");
        if (off < 0 || off > buf.length - len) throw new IndexOutOfBoundsException("buf");
    }
}
