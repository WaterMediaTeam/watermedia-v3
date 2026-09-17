package org.watermedia;

import org.watermedia.WaterMedia.BootStatus;
import org.watermedia.WaterMedia.BootStatus.Failure;
import org.watermedia.WaterMedia.BootStatus.Id;
import org.watermedia.WaterMedia.BootStatus.Outcome;
import org.watermedia.WaterMedia.BootStatus.Progress;
import org.watermedia.WaterMedia.BootStatus.State;
import org.watermedia.tools.IOTool;

import java.util.EnumSet;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Internal module lifecycle contract; only WaterMedia coordinates module instances. */
public abstract class WaterMediaModule {
    private Progress progress = Progress.NONE;
    private final List<Problem> failures = new ArrayList<>();
    private Consumer<Progress> observer = ignored -> {};

    protected abstract void start(WaterMedia context) throws Exception;
    protected void release(final WaterMedia context) throws Exception {}

    protected final synchronized void task(final int step, final int total, final String name) {
        this.progress = new Progress(step, total, Objects.requireNonNull(name), 0, 0, "", false);
        this.observer.accept(this.progress);
    }

    protected final synchronized void work(final String name, final long done, final long total, final boolean remote) {
        final Progress previous = this.progress;
        this.progress = new Progress(previous.taskStep(), previous.taskSteps(), previous.taskName(),
                done, total, Objects.requireNonNull(name), remote);
        this.observer.accept(this.progress);
    }

    protected final synchronized void failure(final String task, final Throwable cause) {
        this.failures.add(new Problem(task, Objects.requireNonNull(cause)));
        this.observer.accept(this.progress);
    }

    final synchronized void observe(final Consumer<Progress> observer) { this.observer = observer; }
    final synchronized List<Problem> failures() { return List.copyOf(this.failures); }

    record Problem(String task, Throwable cause) {}

    // EACH SESSION OWNS ITS MODULES AND PUBLISHES IMMUTABLE SNAPSHOTS TO OTHER THREADS.
    static final class Bootstrap {
        record Definition(Id id, boolean client, boolean essential, List<Id> required,
                          Supplier<WaterMediaModule> factory) {
            Definition {
                Objects.requireNonNull(id);
                Objects.requireNonNull(factory);
                required = List.copyOf(required);
            }
        }

        private final List<Definition> definitions;
        private final WaterMediaModule[] instances;
        private final List<BootStatus.Module> modules = new ArrayList<>();
        private final List<List<Failure>> problems = new ArrayList<>();
        private final List<Failure> terminal = new ArrayList<>();
        private volatile BootStatus status;
        private State state = State.STOPPED;
        private int current = -1;
        private Progress progress = Progress.NONE;

        Bootstrap(final List<Definition> definitions) {
            this.definitions = List.copyOf(definitions);
            this.instances = new WaterMediaModule[definitions.size()];
            final EnumSet<Id> seen = EnumSet.noneOf(Id.class);
            for (final Definition definition: definitions) {
                if (seen.contains(definition.id()) || !seen.containsAll(definition.required()))
                    throw new IllegalArgumentException("Duplicate or unordered bootstrap dependency: " + definition.id());
                seen.add(definition.id());
                this.modules.add(new BootStatus.Module(definition.id(), Outcome.PENDING, null, ""));
                this.problems.add(List.of());
            }
            this.publish();
        }

        BootStatus status() { return this.status; }

        synchronized void starting() {
            this.state = State.STARTING;
            this.publish();
        }

        synchronized void stopping() {
            this.state = State.STOPPING;
            this.publish();
        }

        void start(final WaterMedia context) {
            this.starting();
            Id essentialFailure = null;
            boolean interrupted = false;
            Error fatal = null;
            for (int i = 0; i < this.definitions.size(); i++) {
                final Definition definition = this.definitions.get(i);
                this.select(i);
                if (essentialFailure != null) {
                    this.outcome(i, Outcome.BLOCKED, essentialFailure, "Essential startup failed");
                    continue;
                }
                if (definition.client() && !context.clientSide) {
                    this.outcome(i, Outcome.SKIPPED, null, "Client service");
                    continue;
                }
                Id missing = null;
                for (final Id dependency: definition.required()) {
                    if (this.modules.stream().noneMatch(module -> module.id() == dependency && module.outcome() == Outcome.READY)) {
                        missing = dependency;
                        break;
                    }
                }
                if (missing != null) {
                    this.outcome(i, Outcome.BLOCKED, missing, "Required service is unavailable");
                    if (definition.essential()) essentialFailure = definition.id();
                    continue;
                }
                this.outcome(i, Outcome.STARTING, null, "");
                try {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Bootstrap interrupted");
                    final WaterMediaModule module = Objects.requireNonNull(definition.factory().get(), "module");
                    this.instances[i] = module;
                    final int index = i;
                    module.observe(value -> this.update(index, value, module.failures()));
                    module.start(context);
                    this.outcome(i, Outcome.READY, null, "");
                } catch (final Exception | LinkageError failure) {
                    this.failed(i, this.status.progress().taskName(), failure);
                    interrupted |= failure instanceof InterruptedException || Thread.currentThread().isInterrupted();
                    if (definition.essential() || interrupted) essentialFailure = definition.id();
                } catch (final Error failure) {
                    this.failed(i, this.status.progress().taskName(), failure);
                    essentialFailure = definition.id();
                    fatal = failure;
                }
            }
            if (essentialFailure != null) {
                final boolean restoreInterrupt = Thread.interrupted() || interrupted;
                try {
                    this.unwind(context);
                } catch (final Error cleanup) {
                    fatal = (Error) IOTool.mergeFailure(fatal, cleanup);
                } finally {
                    synchronized (this) {
                        this.state = State.FAILED;
                        this.select(-1);
                        this.publish();
                    }
                    if (restoreInterrupt) Thread.currentThread().interrupt();
                }
                if (fatal != null) throw fatal;
                throw new IllegalStateException("Essential WaterMedia startup failed: " + essentialFailure,
                        this.status.failures().isEmpty() ? null : this.status.failures().get(0).cause());
            }
            synchronized (this) {
                this.state = this.status.failures().isEmpty()
                        && this.modules.stream().noneMatch(module -> module.outcome() == Outcome.BLOCKED || module.outcome() == Outcome.FAILED)
                        ? State.READY : State.DEGRADED;
                this.publish();
            }
        }

        void stop(final WaterMedia context) {
            this.stopping();
            this.unwind(context);
            synchronized (this) {
                this.select(-1);
                this.state = this.ownsResources() ? State.FAILED : State.STOPPED;
                this.publish();
            }
            if (this.ownsResources()) throw new IllegalStateException("WaterMedia shutdown has unfinished resources; retry stop after inspecting status()");
        }

        boolean ownsResources() {
            for (final WaterMediaModule module: this.instances) if (module != null) return true;
            return false;
        }

        private void unwind(final WaterMedia context) {
            Error fatal = null;
            Id waiting = null;
            // A MODULE THAT CANNOT CLOSE RETAINS ITS PREREQUISITES UNTIL A LATER STOP RETRIES IT.
            for (int i = this.instances.length - 1; i >= 0; i--) {
                final WaterMediaModule module = this.instances[i];
                if (module == null) continue;
                if (waiting != null) {
                    this.outcome(i, Outcome.BLOCKED, waiting, "Shutdown deferred until later resources close");
                    continue;
                }
                this.select(i);
                final BootStatus.Module previous = this.modules.get(i);
                try {
                    module.release(context);
                    module.observe(ignored -> {});
                    this.instances[i] = null;
                    if (this.state == State.STOPPING || previous.outcome() == Outcome.READY)
                        this.outcome(i, Outcome.STOPPED, null, "");
                } catch (final Exception | LinkageError failure) {
                    this.failed(i, "Shutdown", failure);
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    waiting = previous.id();
                } catch (final Error failure) {
                    this.failed(i, "Shutdown", failure);
                    waiting = previous.id();
                    fatal = (Error) IOTool.mergeFailure(fatal, failure);
                }
            }
            if (fatal != null) {
                synchronized (this) {
                    this.state = State.FAILED;
                    this.publish();
                }
                throw fatal;
            }
        }

        private synchronized void update(final int index, final Progress progress, final List<WaterMediaModule.Problem> failures) {
            if (this.current != index || (this.state != State.STARTING && this.state != State.STOPPING)) return;
            this.progress = progress;
            this.problems.set(index, failures.stream()
                    .map(failure -> new Failure(this.definitions.get(index).id(), failure.task(), failure.cause())).toList());
            this.publish();
        }

        private synchronized void failed(final int index, final String task, final Throwable failure) {
            this.terminal.add(new Failure(this.definitions.get(index).id(), task, failure));
            this.outcome(index, Outcome.FAILED, null, failure.getClass().getSimpleName());
        }

        private synchronized void outcome(final int index, final Outcome outcome, final Id dependency, final String reason) {
            this.modules.set(index, new BootStatus.Module(this.definitions.get(index).id(), outcome, dependency, reason));
            this.publish();
        }

        private synchronized void publish() {
            final List<Failure> failures = new ArrayList<>();
            for (final List<Failure> module: this.problems) failures.addAll(module);
            failures.addAll(this.terminal);
            this.status = new BootStatus(this.state, this.current + 1, this.definitions.size(),
                    this.current < 0 ? null : this.definitions.get(this.current).id(), this.progress, this.modules, failures);
        }

        private synchronized void select(final int index) {
            this.current = index;
            this.progress = Progress.NONE;
        }
    }
}
