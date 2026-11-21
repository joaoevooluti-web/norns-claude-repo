package org.nornsmobile.app;

/**
 * Normaliza os controles touch do painel Norns antes de encaminhá-los ao LuaHost.
 * Chamado pela thread de UI; o sink do host enfileira os eventos na thread Lua.
 */
final class NornsInputDispatcher {
    interface Sink {
        void onKey(int n, int z);
        void onEnc(int n, int d);
    }

    private final Sink sink;
    private final boolean[] pressed = new boolean[4];

    NornsInputDispatcher(Sink sink) {
        if (sink == null) throw new IllegalArgumentException("sink não pode ser null");
        this.sink = sink;
    }

    boolean keyDown(int n) {
        if (!validControl(n) || pressed[n]) return false;
        pressed[n] = true;
        sink.onKey(n, 1);
        return true;
    }

    boolean keyUp(int n) {
        if (!validControl(n) || !pressed[n]) return false;
        pressed[n] = false;
        sink.onKey(n, 0);
        return true;
    }

    void releaseAll() {
        for (int n = 1; n <= 3; n++) keyUp(n);
    }

    boolean encoderDelta(int n, int delta) {
        if (!validControl(n) || delta == 0) return false;
        sink.onEnc(n, delta);
        return true;
    }

    boolean isPressed(int n) {
        return validControl(n) && pressed[n];
    }

    private boolean validControl(int n) {
        return n >= 1 && n <= 3;
    }
}
