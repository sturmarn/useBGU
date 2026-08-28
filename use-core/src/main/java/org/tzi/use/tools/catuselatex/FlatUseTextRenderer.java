package org.tzi.use.tools.catuselatex;

import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.expr.*;
import org.tzi.use.uml.ocl.type.EnumType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Renders the fully resolved, flattened plain-USE view of a compiled
 * {@link MMultiLevelModel} -- the third stage, after CatUSE and desugared
 * MLM-USE: what the underlying OCL evaluator actually sees.
 *
 * <p>Every class (category or clabject, at any level) becomes an ordinary
 * flat {@code class} declaration listing its fully resolved attributes
 * ({@link MClass#allAttributes()}, cancellations already applied) -- with
 * no {@code class X < Y} inheritance at all. Plain USE inheritance has no
 * way to express the removal a clabject performs: an inherited
 * attribute/role/constraint can be added to, but never subtracted from, a
 * plain USE subclass. That asymmetry is exactly why the clabject construct
 * exists; reusing "extends" here would silently put the cancelled features
 * back. This is the same flattening
 * {@code MInternalClassImpl#allAttributes()}/{@code #navigableEnds()}
 * already perform on demand for OCL evaluation -- this renderer just makes
 * it visible as text.
 *
 * <p><b>Scope of the association reconstruction:</b> each association is
 * printed once, at its own originally declared endpoint classes -- always
 * correct for those classes, since a clabject's cancellation list can only
 * ever target the clabject itself, never the category/class an association
 * was declared on. A descendant clabject that inherits one of these roles
 * without cancelling it (so, without redeclaring it locally either) is
 * noted with a comment instead of a duplicated association block, to keep
 * this a bounded, transparent tool rather than attempting a fully general
 * n-ary/multi-clabject reconstruction.
 *
 * <p><b>Name disambiguation:</b> a class's internal name is "Level@Class"
 * (level first) -- but plain USE identifiers can't contain "@" at all, and
 * a flattened model merges every level's classes into one single
 * {@code model} block, so two different levels' classes sharing a short
 * name (legitimate and common in MLM-USE, since each level has its own
 * independent namespace) would otherwise both print as the same bare
 * {@code class X}, producing invalid, non-re-parseable output ("Redefinition
 * of X"). Classes are therefore printed under their bare short name only
 * when that name is unique across the whole flattened model; a colliding
 * name is printed instead as {@code ClassName__AT__LevelName} (class first,
 * synthesized separator last -- see {@link #flatten}), a legal plain-USE
 * identifier. "__AT__" is reserved for this purpose: see
 * {@link #checkNoReservedTokens}.
 */
public class FlatUseTextRenderer {

    /**
     * "__AT__" is reserved for this renderer's own synthesized
     * disambiguation names. A user's own identifier may still contain the
     * substring as long as it isn't exactly this token -- i.e. it's fine
     * if flanked by an extra underscore on either side (that makes the
     * underscore run three-or-more long, no longer an exact match):
     * "this___AT__is_ok" and "this__AT__is_ok__" are fine, "this__AT__is_not"
     * is not, because there its underscore runs immediately around "AT" are
     * exactly two on both sides.
     */
    private static final Pattern RESERVED_AT_TOKEN = Pattern.compile("(?<!_)__AT__(?!_)");

    public static String render(MMultiLevelModel mlm, String displayName) {
        checkNoReservedTokens(mlm);

        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        out.println("model " + displayName);
        out.println();

        // Enum types: a per-level enum is declared inside that level's own
        // model block (its literals/name live in level.enumTypes()), while
        // an inter-enum -- owned by no single constituent model, typing an
        // attribute on an inter-class -- lives in the multi-level model's
        // own enumTypes() instead (see MMultiLevelModel's "steal fields"
        // constructor, which copies exactly the inter ones there). Neither
        // was ever printed here at all before this fix, so any class
        // referencing an enum type -- inter or per-level -- pointed at a
        // type the flattened output never declared. Enum names aren't
        // "Level@Name" qualified the way class names are (they're already
        // flat/global identifiers -- see e.g. EnumViaInterEnum.use), so no
        // flatten()/shortNameCounts disambiguation is needed for them.
        Map<String, EnumType> enumsByName = new TreeMap<>();
        for (EnumType et : mlm.enumTypes()) {
            enumsByName.put(et.name(), et);
        }
        for (MModel level : mlm.models()) {
            for (EnumType et : level.enumTypes()) {
                enumsByName.put(et.name(), et);
            }
        }
        for (EnumType et : enumsByName.values()) {
            printEnumType(out, et);
        }
        if (!enumsByName.isEmpty()) {
            out.println();
        }

        List<MClass> allClasses = new ArrayList<>();
        for (MModel level : orderedLevels(mlm)) {
            List<MClass> classes = new ArrayList<>(level.classes());
            classes.sort(Comparator.comparing(MClass::name));
            allClasses.addAll(classes);
        }
        // Inter-classes -- owned by no single constituent model -- were
        // never added here at all, so an inter-association referencing one
        // (now printed, per the fix above) used to end up pointing at a
        // class the flattened output never declared in the first place.
        List<MClass> interClasses = new ArrayList<>(mlm.interClasses());
        interClasses.sort(Comparator.comparing(MClass::name));
        allClasses.addAll(interClasses);

        Map<String, Long> shortNameCounts = new HashMap<>();
        for (MClass cls : allClasses) {
            shortNameCounts.merge(classPart(cls.name()), 1L, Long::sum);
        }

        // An association class is simultaneously an MClass and an
        // MAssociation (MAssociationClassImpl implements both), and
        // mlm.interClasses()/mlm.interAssociations() both hand it back --
        // it's registered in the multi-model's fClasses AND fAssociations
        // maps at once. Printed through the generic class loop below AND
        // the generic association loop, that produced two conflicting
        // declarations of the same name ("Model already contains a class
        // `Membership'"). It's printed exactly once instead, via its own
        // proper "associationclass ... between ... end" form -- skipped in
        // both generic loops below.
        for (MClass cls : allClasses) {
            if (cls instanceof MAssociationClass) {
                printAssociationClass(out, (MAssociationClass) cls, shortNameCounts);
            } else {
                printClass(out, cls, mlm, shortNameCounts);
            }
        }

        for (MModel level : orderedLevels(mlm)) {
            List<MAssociation> assocs = new ArrayList<>(level.associations());
            assocs.sort(Comparator.comparing(MAssociation::name));
            for (MAssociation assoc : assocs) {
                if (assoc instanceof MAssociationClass) continue;
                printAssociation(out, assoc, shortNameCounts);
            }
        }
        // Inter-associations connect classes across two different
        // constituent models directly (no clabject/mediator relationship
        // between the models involved) -- omitted here previously, which
        // silently dropped them from the flattened output entirely.
        List<MAssociation> interAssocs = new ArrayList<>(mlm.interAssociations());
        interAssocs.sort(Comparator.comparing(MAssociation::name));
        for (MAssociation assoc : interAssocs) {
            if (assoc instanceof MAssociationClass) continue;
            printAssociation(out, assoc, shortNameCounts);
        }
        out.println();

        List<MClassInvariant> localInvariants = new ArrayList<>();
        for (MClass cls : allClasses) {
            for (MClassInvariant inv : mlm.allClassInvariants(cls)) {
                if (inv.cls().equals(cls)) {
                    localInvariants.add(inv);
                }
            }
        }
        if (!localInvariants.isEmpty()) {
            out.println("constraints");
            for (MClassInvariant inv : localInvariants) {
                printInvariant(out, inv, shortNameCounts);
            }
        }

        out.flush();
        return sw.toString();
    }

    /**
     * Rejects a model that already uses the "__AT__" token this renderer
     * reserves for its own synthesized disambiguation names (see the class
     * doc comment) -- in a level name, or in any class's own short name.
     * Checking this once up front, before rendering anything, means a
     * violation is reported as one clear error rather than surfacing later
     * as a confusing double meaning in the flattened output.
     */
    private static void checkNoReservedTokens(MMultiLevelModel mlm) {
        for (MModel level : mlm.models()) {
            if (RESERVED_AT_TOKEN.matcher(level.name()).find()) {
                throw new IllegalArgumentException("Level name \"" + level.name()
                        + "\" uses \"__AT__\", which is reserved for FlatUseTextRenderer's own "
                        + "disambiguation names. Use e.g. \"_\" or \"___AT__\"/\"__AT___\" instead.");
            }
            for (MClass cls : level.classes()) {
                String shortName = classPart(cls.name());
                if (RESERVED_AT_TOKEN.matcher(shortName).find()) {
                    throw new IllegalArgumentException("Class name \"" + shortName
                            + "\" (in level \"" + level.name() + "\") uses \"__AT__\", which is reserved "
                            + "for FlatUseTextRenderer's own disambiguation names. Use e.g. \"_\" or "
                            + "\"___AT__\"/\"__AT___\" instead.");
                }
            }
        }
    }

    private static void printClass(PrintWriter out, MClass cls, MMultiLevelModel mlm,
                                    Map<String, Long> shortNameCounts) {
        // Direct parents that can be losslessly represented as plain-USE
        // "<" inheritance: a same-level generalization edge always can (it
        // has no cancellation mechanism at all, so a subclass unconditionally
        // gets everything); a clabject edge only can when it renames/removes
        // nothing -- see safePassThroughParents's own doc comment for why
        // this distinction matters and what breaks without it.
        List<MClass> safeParents = safePassThroughParents(cls, mlm);

        StringBuilder header = new StringBuilder("class ").append(flatten(cls.name(), shortNameCounts));
        if (!safeParents.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (MClass p : safeParents) names.add(flatten(p.name(), shortNameCounts));
            header.append(" < ").append(String.join(", ", names));
        }
        out.println(header);

        // Attributes already supplied by a safeParent will arrive via the
        // "<" just printed, and must NOT also be restated here, or the
        // flattened output would redeclare the same attribute twice.
        // Compared by NAME, not object identity or equals(): identity fails
        // because MInternalClassImpl.allAttributes() is uncached, so calling
        // it twice for the same class (once here, once for cls's own list)
        // hands back two distinct MInternalAttribute objects for a renamed
        // attribute (confirmed empirically -- see known-issues.org); and
        // MInternalAttribute's own equals() is separately known-asymmetric
        // with its superclass. Name comparison sidesteps both: attribute
        // names are already unique across a class's whole specialization
        // hierarchy (enforced by MClassImpl.addAttribute()'s own conflict
        // check), so in any model that compiled at all, the same name
        // reaching cls always means the same conceptual attribute.
        Set<String> suppliedBySafeParent = new HashSet<>();
        for (MClass p : safeParents) {
            for (MAttribute a : p.allAttributes()) suppliedBySafeParent.add(a.name());
        }

        List<MAttribute> attrs = new ArrayList<>();
        for (MAttribute a : cls.allAttributes()) {
            if (!suppliedBySafeParent.contains(a.name())) attrs.add(a);
        }
        if (!attrs.isEmpty()) {
            attrs.sort(Comparator.comparing(MAttribute::name));
            out.println("attributes");
            for (MAttribute attr : attrs) {
                out.println("  " + attr.name() + " : " + flatten(attr.type().toString(), shortNameCounts));
            }
        }
        out.println("end");

        // Same reasoning for roles: a role navigable through a safeParent
        // arrives automatically via "<", so it drops out of the "not
        // restated" note below; only a role that genuinely has no
        // plain-USE-expressible path (from a renaming/cancelling clabject
        // parent) still needs the comment.
        Set<String> roleSuppliedBySafeParent = new HashSet<>();
        for (MClass p : safeParents) roleSuppliedBySafeParent.addAll(p.navigableEnds().keySet());

        Set<String> inheritedRoles = new TreeSet<>(cls.navigableEnds().keySet());
        if (cls instanceof MInternalClassImpl) {
            inheritedRoles.removeAll(((MInternalClassImpl) cls).navigableElements().keySet());
        }
        inheritedRoles.removeAll(roleSuppliedBySafeParent);

        // Of what's left (reachable only through a renaming/cancelling
        // clabject parent, so "<" is off the table -- see
        // safePassThroughParents), a genuine SELF-association (every end
        // originally declared on the very class this clabject renamed) can
        // still be made reachable: restate it with every end retyped to
        // cls's own flattened name, since cls has no "<" relation to the
        // original declaring class in this output and so cannot otherwise
        // participate in an association typed by that class at all. Left
        // as a "not restated" comment for anything less uniform (a
        // multi-class association, or one only partially reachable) --
        // this stays a bounded, transparent restatement, not an attempt at
        // a fully general n-ary/multi-clabject reconstruction.
        Set<MAssociation> assocsToRestate = new LinkedHashSet<>();
        Set<String> stillUnrestated = new TreeSet<>();
        for (String role : inheritedRoles) {
            MNavigableElement nav = cls.navigableEnds().get(role);
            MAssociation assoc = nav.association();
            boolean pureSelfAssociation = true;
            MClass declaringClass = null;
            for (MAssociationEnd end : assoc.associationEnds()) {
                if (declaringClass == null) {
                    declaringClass = end.cls();
                } else if (!declaringClass.equals(end.cls())) {
                    pureSelfAssociation = false;
                    break;
                }
            }
            if (pureSelfAssociation) {
                assocsToRestate.add(assoc);
            } else {
                stillUnrestated.add(role);
            }
        }
        for (MAssociation assoc : assocsToRestate) {
            printAssociationRetyped(out, assoc, cls, shortNameCounts);
        }
        if (!stillUnrestated.isEmpty()) {
            out.println("-- " + flatten(cls.name(), shortNameCounts) + " also inherits navigable role(s) "
                    + String.join(", ", stillUnrestated)
                    + " from its powerclass (not restated as a separate association here)");
        }

        // And for invariants: plain USE already re-checks a superclass's
        // own invariant against every subclass automatically, so an
        // invariant declared directly on a safeParent needs no comment --
        // it's genuinely, automatically inherited via the "<" above, not
        // merely noted as such.
        List<String> inheritedInvariants = new ArrayList<>();
        for (MClassInvariant inv : mlm.allClassInvariants(cls)) {
            if (!inv.cls().equals(cls) && !safeParents.contains(inv.cls())) {
                inheritedInvariants.add(flatten(inv.name(), shortNameCounts));
            }
        }
        if (!inheritedInvariants.isEmpty()) {
            Collections.sort(inheritedInvariants);
            out.println("-- " + flatten(cls.name(), shortNameCounts) + " also inherits invariant(s) "
                    + String.join(", ", inheritedInvariants)
                    + " from its powerclass (printed once, at its origin, below)");
        }
        out.println();
    }

    /**
     * Direct parents of {@code cls} that plain-USE "&lt;" can represent
     * without changing what's reachable through them: a same-level
     * generalization edge always qualifies (plain USE inheritance has no
     * cancellation mechanism at all, so nothing is ever lost); a clabject
     * edge qualifies only when it renames or removes nothing at all, since
     * plain "&lt;" -- unlike a clabject -- cannot subtract or rename an
     * inherited attribute/role/constraint (see this file's class-level
     * doc comment for why that asymmetry exists in the first place).
     * Getting this distinction wrong in either direction is a real bug,
     * not a style choice: using "&lt;" for a renaming/cancelling clabject
     * would silently resurrect what it removed; never using "&lt;" at all
     * (this method's precursor) leaves a pass-through class's inherited
     * associations unreachable in the flattened, re-parsed model, since
     * they were only ever explained in a comment.
     */
    private static List<MClass> safePassThroughParents(MClass cls, MMultiLevelModel mlm) {
        List<MClass> result = new ArrayList<>();
        for (MClass parent : cls.parents()) {
            Set<MGeneralization> edges = mlm.generalizationGraph().edgesBetween(cls, parent);
            MGeneralization edge = edges.stream().findFirst().orElse(null);
            if (edge == null) continue;
            if (!(edge instanceof MClabject)) {
                // A same-level "class Child < Parent" edge: always safe.
                result.add(parent);
                continue;
            }
            MClabject clabject = (MClabject) edge;
            if (clabject.getRemovedAttributes().isEmpty()
                    && clabject.getAttributeRenaming().isEmpty()
                    && clabject.getRemovedConstraints().isEmpty()
                    && clabject.getRemovedRoles().isEmpty()) {
                result.add(parent);
            }
        }
        return result;
    }

    private static void printAssociation(PrintWriter out, MAssociation assoc, Map<String, Long> shortNameCounts) {
        out.println("association " + flatten(assoc.name(), shortNameCounts) + " between");
        for (MAssociationEnd end : assoc.associationEnds()) {
            out.print("  " + flatten(end.cls().name(), shortNameCounts) + "[" + end.multiplicity() + "] role " + end.name());
            out.println();
        }
        out.println("end");
        out.println();
    }

    /**
     * Prints one enum type declaration. Enum type names are already flat,
     * global identifiers -- unlike class names, they carry no "Level@"
     * qualification and so need no flatten()/shortNameCounts handling (see
     * the call site's comment for why).
     */
    private static void printEnumType(PrintWriter out, EnumType et) {
        List<String> literals = new ArrayList<>();
        et.literals().forEachRemaining(literals::add);
        out.println("enum " + et.name() + " {" + String.join(", ", literals) + "}");
    }

    /**
     * Prints an association class -- an {@code MAssociationClass} is
     * simultaneously an {@code MClass} and an {@code MAssociation} -- as a
     * single plain-USE {@code associationclass ... between ... end} block,
     * exactly once. Currently reachable only through inter-classes (a
     * {@code model ... end} block declaring one is rejected at compile time
     * -- see known-issues.org, Issue 1), so unlike printClass, this doesn't
     * need to handle inheritance/cancellation at all. Its invariants, like
     * any other class's, are printed separately in the trailing
     * {@code constraints} section by the caller (it's kept in allClasses
     * for exactly that reason), so this only needs the ends and attributes.
     */
    private static void printAssociationClass(PrintWriter out, MAssociationClass ac,
                                                Map<String, Long> shortNameCounts) {
        out.println("associationclass " + flatten(ac.name(), shortNameCounts) + " between");
        for (MAssociationEnd end : ac.associationEnds()) {
            out.println("  " + flatten(end.cls().name(), shortNameCounts) + "[" + end.multiplicity() + "] role " + end.name());
        }
        List<MAttribute> attrs = new ArrayList<>(ac.allAttributes());
        attrs.sort(Comparator.comparing(MAttribute::name));
        if (!attrs.isEmpty()) {
            out.println("attributes");
            for (MAttribute attr : attrs) {
                out.println("  " + attr.name() + " : " + flatten(attr.type().toString(), shortNameCounts));
            }
        }
        out.println("end");
        out.println();
    }

    /**
     * Restates a genuine self-association (every end originally declared on
     * the same class) for a descendant class that inherits it through a
     * renaming/cancelling clabject edge -- see printClass's own comment on
     * why "&lt;" can't be used to make it reachable there instead. Every
     * end is retyped to {@code cls}'s own flattened name (the only option:
     * cls has no "&lt;" relation to the original declaring class in this
     * output, so it cannot otherwise participate in an association typed
     * by that class at all), keeping the original role names and
     * multiplicities. Named "OriginalName__AT__ClassName" to avoid
     * colliding with the association already printed once at its own
     * original declaring class.
     */
    private static void printAssociationRetyped(PrintWriter out, MAssociation assoc, MClass cls,
                                                  Map<String, Long> shortNameCounts) {
        String clsName = flatten(cls.name(), shortNameCounts);
        out.println("association " + flatten(assoc.name(), shortNameCounts) + "__AT__" + clsName + " between");
        for (MAssociationEnd end : assoc.associationEnds()) {
            out.print("  " + clsName + "[" + end.multiplicity() + "] role " + end.name());
            out.println();
        }
        out.println("end");
        out.println();
    }

    /**
     * An {@link ExpressionPrintVisitor} that omits a query iterator
     * variable's explicit type annotation -- e.g. prints
     * "forAll(n | ...)" rather than the base class's default
     * "forAll(n:Vertex | ...)". Plain OCL syntax always makes this
     * annotation optional (see USE's own grammar: {@code
     * elemVarsDeclaration}'s {@code (COLON type)?} is optional, unlike
     * {@code variableInitialization}'s, which is mandatory -- see below),
     * and the iterator's type is always re-inferable from the actual
     * (possibly retyped, in this renderer's flattened output) collection
     * it ranges over. Printing the ORIGINAL model's frozen annotation
     * verbatim -- what the base class does -- can therefore describe a
     * type that no longer matches the flattened collection's element type
     * at all: printAssociationRetyped (see its own doc comment) retypes a
     * self-association's ends to the inheriting class for reachability,
     * but an invariant using that association was already compiled
     * against its association's ORIGINAL declared element type, and the
     * compiled expression tree -- which is all this renderer ever
     * re-prints -- keeps that original type forever. (Example: known-
     * issues.org's GraphColoring -- ColoredVertex inherits Vertex's
     * self-association AdjacentTo through a renaming clabject edge, so
     * it's restated retyped to ColoredVertex; but ProperColoring's
     * "self.neighbors->forAll(n | ...)" was compiled when "neighbors"
     * still meant Set(Vertex), so the base printer's "n:Vertex" no longer
     * matches the retyped Set(ColoredVertex) it actually ranges over in
     * the flattened text, and plain USE rejects the mismatch outright.)
     *
     * <p>The accumulator variable of an "iterate(...; acc:T = init | ...)"
     * is left fully typed -- unlike a query iterator variable, plain OCL
     * requires its type explicitly, so it can't be omitted at all.
     *
     * <p>Suppression is scoped tightly around printing just the iterator
     * variable declaration(s) of the query expression actually being
     * visited -- restored to its prior value before the accumulator
     * (if any) and the body are printed -- so it never leaks into a
     * nested query expression's own (independently suppressed) iterator
     * variable.
     */
    private static class RetypingSafeExpressionPrintVisitor extends ExpressionPrintVisitor {
        private boolean suppressVarDeclType = false;

        RetypingSafeExpressionPrintVisitor(PrintWriter writer) {
            super(writer);
        }

        @Override
        public void visitVarDecl(VarDecl varDecl) {
            if (suppressVarDeclType) {
                writer.write(variable(varDecl.name(), null));
            } else {
                super.visitVarDecl(varDecl);
            }
        }

        private void printQueryOmittingIteratorType(ExpQuery exp, VarInitializer accuInit) {
            exp.getRangeExpression().processWithVisitor(this);
            writer.write(operator("->", exp));
            writer.write(operation(exp.name(), exp));
            writer.write(operator("(", exp));
            writer.write(ws());

            boolean prev = suppressVarDeclType;
            suppressVarDeclType = true;
            exp.getVariableDeclarations().processWithVisitor(this);
            suppressVarDeclType = prev;

            if (accuInit != null) {
                writer.write(operator(";", exp));
                writer.write(ws());
                accuInit.getVarDecl().processWithVisitor(this);
                writer.write(operator("=", exp));
                accuInit.initExpr().processWithVisitor(this);
            }
            writer.write(ws());
            writer.write(operator("|", exp));
            writer.write(ws());
            exp.getQueryExpression().processWithVisitor(this);
            writer.write(ws());
            writer.write(operator(")", exp));
        }

        @Override public void visitQuery(ExpQuery exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitForAll(ExpForAll exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitExists(ExpExists exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitSelect(ExpSelect exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitReject(ExpReject exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitCollect(ExpCollect exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitCollectNested(ExpCollectNested exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitIsUnique(ExpIsUnique exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitSortedBy(ExpSortedBy exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitOne(ExpOne exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitAny(ExpAny exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitClosure(ExpClosure exp) { printQueryOmittingIteratorType(exp, null); }
        @Override public void visitIterate(ExpIterate exp) { printQueryOmittingIteratorType(exp, exp.getAccuInitializer()); }
    }

    private static void printInvariant(PrintWriter out, MClassInvariant inv, Map<String, Long> shortNameCounts) {
        out.print("context " + flatten(inv.cls().name(), shortNameCounts) + " inv " + flatten(inv.name(), shortNameCounts) + ":");
        out.println();
        out.print("  ");
        StringWriter bodySw = new StringWriter();
        PrintWriter bodyPw = new PrintWriter(bodySw);
        ExpressionVisitor visitor = new RetypingSafeExpressionPrintVisitor(bodyPw);
        inv.bodyExpression().processWithVisitor(visitor);
        bodyPw.flush();
        out.println(flatten(bodySw.toString(), shortNameCounts));
        out.println();
    }

    /** The class-name half of an internal "Level@Class" name; unchanged if there is no "@". */
    private static String classPart(String qualifiedName) {
        int at = qualifiedName.indexOf('@');
        return at < 0 ? qualifiedName : qualifiedName.substring(at + 1);
    }

    /**
     * Resolves one already-split "Level@Class" pair to its flattened,
     * plain-USE-legal spelling: the bare class name if it's unique across
     * the whole flattened model, or "Class__AT__Level" (class first) if
     * some other level also has a class with this same short name.
     */
    private static String resolve(String level, String className, Map<String, Long> shortNameCounts) {
        if (shortNameCounts.getOrDefault(className, 0L) > 1) {
            return className + "__AT__" + level;
        }
        return className;
    }

    /**
     * Scans arbitrary text (a type name, an OCL expression body, ...) for
     * every "Level@Class" occurrence and replaces each with its resolved,
     * plain-USE-legal spelling (see {@link #resolve}), leaving everything
     * else untouched.
     */
    private static String flatten(String qualifiedName, Map<String, Long> shortNameCounts) {
        if (qualifiedName.indexOf('@') < 0) {
            return qualifiedName;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < qualifiedName.length()) {
            int at = qualifiedName.indexOf('@', i);
            if (at < 0) {
                sb.append(qualifiedName, i, qualifiedName.length());
                break;
            }
            int start = at;
            while (start > i && Character.isJavaIdentifierPart(qualifiedName.charAt(start - 1))) {
                start--;
            }
            sb.append(qualifiedName, i, start);
            int end = at + 1;
            while (end < qualifiedName.length() && Character.isJavaIdentifierPart(qualifiedName.charAt(end))) {
                end++;
            }
            String level = qualifiedName.substring(start, at);
            String className = qualifiedName.substring(at + 1, end);
            sb.append(resolve(level, className, shortNameCounts));
            i = end;
        }
        return sb.toString();
    }

    /**
     * Parent levels before children, using each mediator's parent link.
     * Levels form a simple chain (each mediator has at most one parent
     * model) in this system, so a depth-by-walking-parents sort is enough.
     */
    private static List<MModel> orderedLevels(MMultiLevelModel mlm) {
        Map<String, MModel> parentOf = new HashMap<>();
        for (MMediator mediator : mlm.mediators()) {
            if (mediator.getParentModel() != null) {
                parentOf.put(mediator.getCurrentModel().name(), mediator.getParentModel());
            }
        }

        Map<String, Integer> depth = new HashMap<>();
        for (MModel model : mlm.models()) {
            int d = 0;
            String curName = model.name();
            Set<String> seen = new HashSet<>();
            while (parentOf.containsKey(curName) && seen.add(curName)) {
                curName = parentOf.get(curName).name();
                d++;
            }
            depth.put(model.name(), d);
        }

        List<MModel> levels = new ArrayList<>(mlm.models());
        levels.sort(Comparator.comparingInt(m -> depth.getOrDefault(m.name(), 0)));
        return levels;
    }
}
