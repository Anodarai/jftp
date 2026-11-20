package com.myjavaworld.jftp;

import java.awt.EventQueue;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Serial work and EDT publication for one live tab. */
final class SessionCoordinator {
    private final ArrayDeque<Operation> pending = new ArrayDeque<>();
    private final ThreadLocal<Operation> origin = new ThreadLocal<>();
    private Operation active;
    private Thread worker;
    private boolean signaling;
    private volatile boolean closed;

    void submit(Runnable begin, Runnable work, Consumer<Throwable> finish) {
        onEdt(() -> {
            if (closed) {
                return;
            }
            pending.addLast(new Operation(begin, work, finish));
            if (active == null && !signaling) {
                startNext();
            }
        });
    }

    private void startNext() {
        Operation next = pending.pollFirst();
        active = next;
        if (next == null || closed) {
            return;
        }
        next.begin().run();
        worker = Thread.startVirtualThread(() -> {
            origin.set(next);
            Throwable failed = null;
            try {
                next.work().run();
            } catch (Throwable failure) {
                failed = failure;
            } finally {
                origin.remove();
                Throwable result = failed;
                EventQueue.invokeLater(() -> complete(next, result));
            }
        });
    }

    private void complete(Operation completed, Throwable failure) {
        if (closed || active != completed) {
            return;
        }
        try {
            completed.finish().accept(failure);
        } finally {
            worker = null;
            active = null;
            if (!signaling) {
                startNext();
            }
        }
    }

    void publish(Runnable result) {
        Operation from = origin.get();
        onEdt(() -> {
            if (!closed && (from == null || active == from)) {
                result.run();
            }
        });
    }

    void signal(Runnable action) {
        onEdt(() -> {
            if (closed || active == null || signaling) {
                return;
            }
            Operation from = active;
            signaling = true;
            Thread.startVirtualThread(() -> {
                origin.set(from);
                try {
                    if (!closed) {
                        action.run();
                    }
                } finally {
                    origin.remove();
                    EventQueue.invokeLater(() -> {
                        signaling = false;
                        if (active == null && !closed) {
                            startNext();
                        }
                    });
                }
            });
        });
    }

    boolean isClosed() {
        return closed;
    }

    void close(Runnable release) {
        onEdt(() -> {
            if (closed) {
                return;
            }
            closed = true;
            pending.clear();
            active = null;
            if (worker != null) {
                worker.interrupt();
                worker = null;
            }
            Thread.startVirtualThread(release);
        });
    }

    private static void onEdt(Runnable task) {
        if (EventQueue.isDispatchThread()) {
            task.run();
        } else {
            EventQueue.invokeLater(task);
        }
    }

    private record Operation(Runnable begin, Runnable work, Consumer<Throwable> finish) {
    }
}
