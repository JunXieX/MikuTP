package com.mikumc.mikutp.common.data;

/**
 * A stored position. The world is kept by name (not UUID) so positions stay
 * meaningful across servers on a network.
 */
public final class Position {

    public String world;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    /** Owning server id; null means "the server this plugin is running on". */
    public String server;

    public Position() {
    }

    public Position(String server, String world, double x, double y, double z, float yaw, float pitch) {
        this.server = server;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** A copy without the server marker, used for positions inside the current server. */
    public static Position local(String world, double x, double y, double z, float yaw, float pitch) {
        return new Position(null, world, x, y, z, yaw, pitch);
    }

    public String shortText() {
        String w = world == null ? "?" : world;
        return w + " " + Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }

    @Override
    public String toString() {
        return "Position{" + (server == null ? "" : server + "/") + shortText() + "}";
    }
}
