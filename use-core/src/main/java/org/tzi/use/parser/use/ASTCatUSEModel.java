package org.tzi.use.parser.use;

import org.antlr.runtime.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse-tree root for the CatMLM surface syntax. Holds the raw,
 * not-yet-desugared levels; {@link USECompilerCatUSE} turns this into a
 * plain {@link ASTMultiLevelModel} before handing off to the existing
 * MLMUse code generation pipeline.
 *
 * @author Claude
 */
public class ASTCatUSEModel {

    private final Token fName;
    private final List<ASTCatLevel> fLevels;
    private final List<ASTAssociation> fInterAssociations;
    private final List<ASTConstraintDefinition> fInterConstraints;
    private final List<ASTPrePost> fInterPrePosts;

    public ASTCatUSEModel(Token name) {
        fName = name;
        fLevels = new ArrayList<>();
        fInterAssociations = new ArrayList<>();
        fInterConstraints = new ArrayList<>();
        fInterPrePosts = new ArrayList<>();
    }

    public void addLevel(ASTCatLevel level) {
        fLevels.add(level);
    }

    /**
     * A genuine inter-level association ("inter-associations" section,
     * outside every catLevel) -- e.g. bookshop_corrected.pl's "catShop",
     * which crosses two levels directly and has no route through the
     * clabject/category mechanism at all. Not to be confused with a
     * catAssociationDefinition parsed inside a catLevel, which stays
     * scoped to that one level.
     */
    public void addInterAssociation(ASTAssociation assoc) {
        fInterAssociations.add(assoc);
    }

    public void addInterConstraint(ASTConstraintDefinition cons) {
        fInterConstraints.add(cons);
    }

    public void addInterPrePost(ASTPrePost prePost) {
        fInterPrePosts.add(prePost);
    }

    public Token name() {
        return fName;
    }

    public List<ASTCatLevel> levels() {
        return fLevels;
    }

    public List<ASTAssociation> interAssociations() {
        return fInterAssociations;
    }

    public List<ASTConstraintDefinition> interConstraints() {
        return fInterConstraints;
    }

    public List<ASTPrePost> interPrePosts() {
        return fInterPrePosts;
    }
}
