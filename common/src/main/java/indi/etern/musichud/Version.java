package indi.etern.musichud;

import indi.etern.musichud.network.ByteBufCodec;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

public record Version(long mayor, long minor, long patch, BuildType build) implements Comparable<Version>{
    public static final ByteBufCodec<Version> PACKET_CODEC;
    // This is an independent project: version numbering restarted at 0.1.0 and no longer
    // follows the upstream MusicHud sequence. leastCapable is the protocol floor checked by
    // compatibleWith(...), so it has to move together with current - leaving it at the old
    // 1.2.2 would make this build refuse to talk to itself.
    public static final Version current = new Version(0,1,4, BuildType.Stable);
    // Deliberately left at 0.1.0: this is a patch release, so a 0.1.4 client must still be able
    // to talk to a 0.1.0 server (and vice versa). compatibleWith() only rejects versions below
    // this floor, so raising it would break connections that still work.
    public static final Version leastCapable = new Version(0,1,0,BuildType.Stable);

    static {
        PACKET_CODEC = new ByteBufCodec<>() {
            public @NotNull Version decode(@NotNull ByteBuf byteBuf) {
                return new Version(byteBuf.readLong(), byteBuf.readLong(), byteBuf.readLong(), BuildType.ofOrdinal(byteBuf.readInt()));
            }

            public void encode(@NotNull ByteBuf byteBuf, @NotNull Version version) {
                byteBuf.writeLong(version.mayor);
                byteBuf.writeLong(version.minor);
                byteBuf.writeLong(version.patch);
                byteBuf.writeInt(version.build.ordinal());
            }
        };
    }

    public enum BuildType {
        Alpha("alpha"), Beta("beta"), PreRelease("pre-release"), Stable("stable");
        final String name;
        BuildType(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }

        public static BuildType ofOrdinal(int o) {
            switch (o) {
                case 0 -> {
                    return Alpha;
                }
                case 1 -> {
                    return Beta;
                }
                case 2 -> {
                    return PreRelease;
                }
                case 3 -> {
                    return Stable;
                }
                default -> {
                    return null;
                }
            }
        }
    }

    @Override
    public @NotNull String toString() {
        return mayor + "." + minor + "." + patch + "-" + build;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Version(
                long mayor1, long minor1, long patch1, BuildType build1
        ) && mayor1 == mayor && minor1 == minor && patch1 == patch && build1 == build;
    }

    @Override
    public int compareTo(@NotNull Version o) {
        if (equals(o)) {
            return 0;
        } else {
            if (mayor > o.mayor) {
                return 4;
            } else if (mayor == o.mayor){
                if (minor > o.minor) {
                    return 3;
                } else if (minor == o.minor){
                    if (patch > o.patch) {
                        return 2;
                    } else if (patch == o.patch){
                        if (build.ordinal() > o.build.ordinal()) {
                            return 1;
                        } else {
                            return -1;
                        }
                    } else {
                        return -2;
                    }
                } else {
                    return -3;
                }
            } else {
                return -4;
            }
        }
    }

    public static boolean compatibleWith(Version v) {
        int i = leastCapable.compareTo(v);
        return i <= 0;
    }
}