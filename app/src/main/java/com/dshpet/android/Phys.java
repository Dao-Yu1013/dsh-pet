package com.dshpet.android;

/** 原版物理参数的 Java 移植。 */
public final class Phys {
    public static final double SQ_MAX = 0.55, SQ_DURATION = 220;

    public static final class P {
        public double gravity = 1400;
        public double restitution = 0.78;
        public double friction = 2.5;
        public boolean ceiling = true;
        public double power = 1;
    }

    public static final class Bounds {
        public final double minX, minY, maxX, maxY;

        Bounds(double minX, double minY, double maxX, double maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }

    private Phys() {
    }

    public static double heightFor(double size) {
        return size * 9.0 / 16.0;
    }

    public static Bounds bounds(double sw, double sh, double size, double sideAllow) {
        return new Bounds(-sideAllow, 0, sw - size + sideAllow, sh - heightFor(size));
    }

    public static double springStep(double v, double x, double target, double dt, double power) {
        return ((((target - x) * 200.0) - (30.0 * v)) * power * dt) + v;
    }

    public static double squashScale(double u, double squash) {
        if (u < 0.45) {
            double d = u / 0.45;
            return 1 - ((1 - squash) * d * d);
        }
        double d3 = (u - 0.45) / 0.55;
        return Math.min(1.12, squash + ((1 - squash) * Math.max((Math.pow(d3 - 1, 3) * 2.70158) + 1 + (Math.pow(d3 - 1, 2) * 1.70158), 0)));
    }

    public static double landingSquash(double impact) {
        return Math.min(0.8, 1 - (0.45 * Math.min(Math.max((Math.abs(impact) - 300) / 1200, 0), 1)));
    }
}
