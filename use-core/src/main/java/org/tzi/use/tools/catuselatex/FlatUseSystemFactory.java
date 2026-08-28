package org.tzi.use.tools.catuselatex;

import org.tzi.use.parser.use.USECompiler;
import org.tzi.use.uml.mm.MMultiLevelModel;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.mm.ModelFactory;
import org.tzi.use.uml.sys.MSystem;

import java.io.PrintWriter;

/**
 * Builds a genuinely runnable {@link MSystem} from a compiled
 * {@link MMultiLevelModel}, by way of {@link FlatUseTextRenderer}.
 *
 * <p>This exists because {@code MSystem}/{@code GGenerator} (the ASSL
 * snapshot generator) and the bundled Kodkod-based model validator plugin
 * are both, architecturally, plain-USE tools: {@code GGenerator} generates
 * against whatever {@code MModel} its {@code MSystem} wraps using ordinary
 * {@code MClass}/{@code MAssociation} navigation, with no notion at all of
 * a clabject's cancellation/renaming or of inter-level constructs -- it was
 * never built multi-level-aware, and making it so directly would be a much
 * larger undertaking than this fork's scope. Handed an {@code MMultiLevelModel}
 * directly (i.e. {@code new MSystem(mlm)}), it happily starts generating
 * against the RAW, unflattened per-level classes -- silently wrong, since a
 * clabject's cancelled attributes/roles/constraints are still fully present
 * there, and inter-level classes/associations/constraints are invisible to
 * it entirely (they live in the multi-level model's own bookkeeping, not
 * any one constituent {@code MModel}).
 *
 * <p>The fix isn't to make the generator multi-level-aware; it's to hand it
 * something it already understands correctly. {@link FlatUseTextRenderer}
 * already computes exactly that: every class's fully resolved,
 * post-cancellation structure, inter-level classes/associations/constraints
 * included, as ordinary plain-USE text with no multi-level constructs left
 * in it at all (see its own class doc comment). Recompiling that text with
 * the ordinary {@link USECompiler} (not {@code USECompilerMLM}) yields a
 * plain {@code MModel} the generator was always designed for, and wrapping
 * that in a fresh {@code MSystem} makes it generatable immediately -- this
 * was confirmed to work end-to-end (a real {@code startProcedure} call
 * against a real ASSL procedure) before this factory existed, as a manual,
 * throwaway proof of concept; this class is that same pipeline made into a
 * permanent, reusable, tested piece of production code.
 *
 * <p>The returned {@code MSystem} is a fresh, empty system over the
 * flattened model -- a different object from any {@code MSystem} the
 * caller may already have wrapping {@code mlm} itself. Generation results
 * (objects, links, invariant checks) live in the returned system, not in
 * whatever system {@code mlm} came from; a caller juggling both needs to
 * keep that distinction straight, exactly as it would when comparing any
 * two independent {@code MSystem} instances.
 */
public final class FlatUseSystemFactory {

    private FlatUseSystemFactory() {}

    /**
     * Flattens {@code mlm} via {@link FlatUseTextRenderer#render} and
     * recompiles the result as plain USE, reporting any recompilation
     * error to {@code err}. A recompilation failure here means the
     * flattening itself produced invalid plain-USE text -- a bug in
     * {@link FlatUseTextRenderer}, not a problem with {@code mlm} (which
     * already compiled successfully as a multi-level model to get here at
     * all) -- so it is reported and returns {@code null} rather than
     * throwing, exactly like {@link USECompiler#compileSpecification}
     * itself does on any other compilation failure.
     *
     * @param mlm         the already-compiled multi-level model to flatten.
     * @param displayName the flattened model's own {@code model} name --
     *                    purely cosmetic (shows up in tool titles/output),
     *                    same role as {@code FlatUseTextRenderer.render}'s
     *                    own {@code displayName} parameter.
     * @param err         where a recompilation failure's errors are
     *                    reported.
     * @return a fresh {@code MSystem} over the flattened model, ready for
     *         {@code system.generator().startProcedure(...)} or any other
     *         plain-USE tool; {@code null} if flattening produced text
     *         that failed to recompile.
     */
    public static MSystem createFlattenedSystem(MMultiLevelModel mlm, String displayName, PrintWriter err) {
        String flatText = FlatUseTextRenderer.render(mlm, displayName);
        MModel flatModel = USECompiler.compileSpecification(flatText, displayName, err, new ModelFactory());
        if (flatModel == null) {
            return null;
        }
        return new MSystem(flatModel);
    }
}
