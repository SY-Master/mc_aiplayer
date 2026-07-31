package io.github.zoyluo.aibot.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pure LIFO model used by TaskManager for nested safety preemption. */
public final class ExecutionStack<T> {
    private final Deque<Frame<T>> frames = new ArrayDeque<>();

    public Frame<T> push(T work, TaskOrigin origin) {
        return push(work, origin, false);
    }

    public Frame<T> push(T work, TaskOrigin origin, boolean explicitResumeOnly) {
        Frame<T> frame = new Frame<>(UUID.randomUUID(), work, origin, explicitResumeOnly);
        frames.addLast(frame);
        return frame;
    }

    public Optional<Frame<T>> peek() {
        return Optional.ofNullable(frames.peekLast());
    }

    /** 自动恢复弹栈:userPaused 时非 safety 帧不弹;explicitResumeOnly 帧(死亡打断)必须显式 resume,不自动弹。 */
    public Optional<Frame<T>> popResumable(boolean userPaused) {
        Frame<T> frame = frames.peekLast();
        if (frame == null || frame.explicitResumeOnly() || (userPaused && !frame.origin().safety())) {
            return Optional.empty();
        }
        return Optional.of(frames.removeLast());
    }

    /** 显式恢复弹栈:不看 explicitResumeOnly(LLM/玩家明确点名要继续这项工作)。 */
    public Optional<Frame<T>> popExplicit() {
        return Optional.ofNullable(frames.pollLast());
    }

    /** 把栈内所有帧标记为"仅显式恢复"(死亡打断:整条暂停链都等 LLM 决定,不再自动接续)。保持原有顺序。 */
    public void markAllExplicitResumeOnly() {
        if (frames.isEmpty()) {
            return;
        }
        List<Frame<T>> all = new ArrayList<>(frames);
        frames.clear();
        for (Frame<T> frame : all) { // ArrayDeque 迭代顺序 = 底→顶,原样推回即保持顺序
            frames.addLast(frame.explicitResumeOnly()
                    ? frame
                    : new Frame<>(frame.frameId(), frame.work(), frame.origin(), true));
        }
    }

    public List<Frame<T>> drain() {
        List<Frame<T>> drained = new ArrayList<>();
        Frame<T> frame;
        while ((frame = frames.pollLast()) != null) {
            drained.add(frame);
        }
        return List.copyOf(drained);
    }

    public int size() {
        return frames.size();
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    /**
     * @param explicitResumeOnly true = 只能被显式 resume(LLM behavior_control action=resume)弹栈,
     *                           系统自动恢复跳过它;用于死亡打断等"该不该继续应由 LLM 决定"的暂停。
     */
    public record Frame<T>(UUID frameId, T work, TaskOrigin origin, boolean explicitResumeOnly) {
    }
}
