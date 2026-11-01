package app.pocketremote.core;

/** Android key codes the TV understands (from the protocol's RemoteKeyCode enum). */
public final class Keys {
    private Keys() {}

    public static final int HOME = 3, BACK = 4;
    public static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, CENTER = 23;
    public static final int VOLUME_UP = 24, VOLUME_DOWN = 25, POWER = 26;
    public static final int ENTER = 66, DEL = 67, MENU = 82, SEARCH = 84;
    public static final int PLAY_PAUSE = 85, STOP = 86, NEXT = 87, PREVIOUS = 88, REWIND = 89, FAST_FORWARD = 90;
    public static final int VOLUME_MUTE = 164, INFO = 165, CHANNEL_UP = 166, CHANNEL_DOWN = 167;
    public static final int SETTINGS = 176, TV_INPUT = 178;
    public static final int HDMI_1 = 243, HDMI_2 = 244, HDMI_3 = 245, HDMI_4 = 246;
}
