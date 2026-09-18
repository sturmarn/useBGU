/*
 * USE - UML based specification environment
 * Copyright (C) 1999-2004 Mark Richters, University of Bremen
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package org.tzi.use.uml.mm;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.tzi.use.uml.mm.commonbehavior.communications.MSignal;
import org.tzi.use.uml.ocl.expr.ExpressionPrintVisitor;
import org.tzi.use.uml.ocl.expr.ExpressionVisitor;
import org.tzi.use.uml.ocl.expr.VarDecl;
import org.tzi.use.uml.ocl.type.EnumType;
import org.tzi.use.uml.sys.soil.MStatement;
import org.tzi.use.util.StringUtil;
import org.tzi.use.util.uml.sorting.UseFileOrderComparator;
import org.tzi.use.util.uml.sorting.UseModelElementFileOrderComparator;

/**
 * Visitor for dumping a string representation of model elements on an
 * output stream. The output respects the concrete syntax rules for a
 * model specification and can be directly fed back into the
 * specification parser.
 *
 * @author      Mark Richters 
 */
public class MMPrintVisitor implements MMVisitor {
    protected PrintWriter fOut;
    private int fIndent;    // number of columns to indent output
    private int fIndentStep = 2;

    /**
     * Level name(s) currently in local scope while printing one level's
     * "model X ... " block or one mediator's "mediator X &lt; Y ... end"
     * block from within {@link #visitMLM}. Multi-level model elements
     * store their name fully qualified as "Level@Name" (to stay distinct
     * across levels), but a declaration/reference local to one of these
     * blocks must use the bare name -- see {@link #localName}. Left empty
     * everywhere else (plain models, the MLM's own inter-* sections, and
     * any element visited standalone rather than via visitMLM), where
     * names are kept fully qualified.
     */
    private final Set<String> fLocalLevels = new LinkedHashSet<>();

    public MMPrintVisitor(PrintWriter out) {
        fOut = out;
        fIndent = 0;
    }

    /**
     * Can be overriden by subclasses to achieve a different output style.
     */
    protected String keyword(String s) {
        return s;
    }

    /**
     * Can be overriden by subclasses to achieve a different output style.
     */
    protected String id(String s) {
        return s;
    }

    /**
     * Can be overriden by subclasses to achieve a different output style.
     */
    protected String other(String s) {
        return s;
    }

    /**
     * Can be overriden by subclasses to achieve a different output style.
     */
    protected void println(String s) {
        fOut.println(s);
    }

    /**
     * Can be overriden by subclasses to achieve a different output style.
     */
    protected void print(String s) {
        fOut.print(s);
    }

    /**
     * Can be overriden by subclasses to achieve a different output style.
     */
    protected void println() {
        fOut.println();
    }

    /**
     * Can be overriden by subclasses to achieve a different output style.
     */
    protected String ws() {
        return " ";
    }

    /**
     * Can be overriden by subclasses to achieve a different output style.
     */
    protected void indent() {
        for (int i = 0; i < fIndent; i++)
            print(ws());
    }

    /**
     * Strips a "Level@" prefix back to the bare name if that level is
     * currently in local scope (see {@link #fLocalLevels}); otherwise
     * returns the name unchanged. Plain (non-multi-level) names never
     * contain "@" and pass through as-is regardless of scope.
     */
    private String localName(String qualifiedName) {
        int at = qualifiedName.indexOf('@');
        if (at < 0) {
            return qualifiedName;
        }
        String level = qualifiedName.substring(0, at);
        return fLocalLevels.contains(level) ? qualifiedName.substring(at + 1) : qualifiedName;
    }

    private static String levelOf(String qualifiedName) {
        int at = qualifiedName.indexOf('@');
        return at < 0 ? "" : qualifiedName.substring(0, at);
    }

    /**
     * A classifier's {@code parents()} mixes two structurally different
     * edges that happen to share the same underlying generalization graph
     * in a multi-level model: genuine same-level "class X &lt; Y"
     * subclassing, and cross-level clabject instance-of edges (the
     * "clabject X : Y" links declared under a mediator). Printing all of
     * them after "&lt;" would produce invalid syntax -- a clabject's
     * powerclass appearing as if it were a same-level superclass. A
     * clabject relates a class to a class exactly one level up by
     * construction, so filtering to same-level parents is enough to
     * recover just the genuine superclasses; for a plain (non-multi-level)
     * classifier every name has the same (empty) level, so this is a
     * no-op.
     */
    private static List<MClassifier> sameLevelParents(MClassifier e) {
        String ownLevel = levelOf(e.name());
        List<MClassifier> result = new ArrayList<>();
        for (MClassifier parent : e.parents()) {
            if (levelOf(parent.name()).equals(ownLevel)) {
                result.add(parent);
            }
        }
        return result;
    }

    private String joinLocalNames(List<MClassifier> classifiers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < classifiers.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(localName(classifiers.get(i).name()));
        }
        return sb.toString();
    }

    @Override
	public void visitAssociation(MAssociation e) {
        visitAnnotations(e);
        indent();
        println(keyword(MAggregationKind.name(e.aggregationKind())) + ws() +
                id(localName(e.name())) + ws() + keyword("between"));

        incIndent();
        
        visitAssociationEnds( e );
        
        println(keyword("end"));
    }

    private void visitAssociationEnds( MAssociation e ) {
        // visit association ends
        for (MAssociationEnd assocEnd : e.associationEnds()) {
            assocEnd.processWithVisitor(this);
        }
        
        decIndent();
        indent();
    }
    
    @Override
	public void visitAssociationClass( MAssociationClass e ) {
    	visitAnnotations(e);
    	indent();
        if ( e.isAbstract() ) {
            print( keyword( "abstract" ) );
            print( ws() );
        }
        
        print( keyword( "associationclass" ) );
        print( ws() );
        print( id( localName(e.name()) ) );

        Set<MAssociationClass> parents = e.parents();
        if ( !parents.isEmpty() ) {
            print( ws() + other( "<" ) + ws() +
                        other( StringUtil.fmtSeq( parents.iterator(), "," ) ) );
        }

        // check parents for definition of roles
        boolean doAssociationEnds = true;
        List<MAssociationEnd> associationEnds = e.associationEnds();
        for(MAssociationClass parent : parents){
        	List<MAssociationEnd> parentAssociationEnds = parent.associationEnds();
        	boolean allEndsTheSame = true;
        	for(int i = 0; i < associationEnds.size(); i++){
        		MAssociationEnd associationEnd = associationEnds.get(i);
        		MAssociationEnd parentAssociationEnd = parentAssociationEnds.get(i);
        		
				if (!associationEnd.name().equals(parentAssociationEnd.name()) && associationEnd.getRedefinedEnds().contains(parentAssociationEnd)) {
        			allEndsTheSame = false;
        			break;
        		}
        	}
        	if(allEndsTheSame){
        		doAssociationEnds = false;
        	}
        }
        if(parents.isEmpty() || doAssociationEnds){
        	// visit aggregation kind
        	if ( e.aggregationKind() == MAggregationKind.NONE ) {
        		// normal association class
        		indent();
        		println( ws() + keyword( "between" ) );
        	} else {
        		// aggregations or composition
        		indent();
        		println( ws() + keyword( MAggregationKind.name( e.aggregationKind() ) ) + ws() +
        				keyword( "between" ) );
        	}
        	incIndent();
        	
        	visitAssociationEnds( e );
        } else {
        	println();
        }
        visitAttributesAndOperations( e );
        
        indent();
        println( keyword( "end" ) );
    }

    @Override
	public void visitAssociationEnd(MAssociationEnd e) {
        visitAnnotations(e);
        StringBuilder result = new StringBuilder();
        
        indent();
        result.append(id(localName(e.cls().name())));
        result.append(other("[" + e.multiplicity() + "]"));
        
        if(!e.cls().nameAsRolename().equals(e.nameAsRolename())){
        	result.append(ws());
        	result.append(keyword("role"));
        	result.append(ws());
        	result.append(id(e.name()));
        }
        
        if (e.hasQualifiers()) {
        	result.append(ws());
        	result.append(keyword("qualifier"));
        	result.append(ws());
        	result.append('(');
        	
        	boolean first = true;
        	for (VarDecl q : e.getQualifiers()) {
        		if (!first){
        			result.append(',');
					result.append(ws());
        		}
        		result.append(q.toString());
        		first = false;
        	}
        	result.append(')');
        }
        
        if (e.getSubsettedEnds().size() > 0) {
        	for (MAssociationEnd end : e.getSubsettedEnds()) {
        		result.append(ws());
        		result.append(keyword("subsets"));
        		result.append(ws());
        		result.append(end.nameAsRolename());
        	}
        }
        
        if (e.getRedefinedEnds().size() > 0) {
        	for (MAssociationEnd end : e.getRedefinedEnds()) {
        		result.append(ws());
        		result.append(keyword("redefines"));
        		result.append(ws());
        		result.append(end.nameAsRolename());
        	}
        }
        
        if (e.isUnion()) {
        	result.append(ws());
        	result.append(keyword("union"));
        }
        
        if (e.isOrdered()) {
        	result.append(ws());
        	result.append(keyword("ordered"));
        }
        
        if (e.isDerived()) {
        	result.append(ws());
        	result.append(keyword("derived"));
        	result.append(ws());
        	result.append(other("="));
        	result.append(ws());
        	print(result.toString());
        	ExpressionVisitor visitor = createExpressionVisitor();
        	e.getDeriveExpression().processWithVisitor(visitor);
        	result = new StringBuilder();
        }
        
        println(result.toString());
    }

    @Override
	public void visitAttribute(MAttribute e) {
        visitAnnotations(e);
        
        indent();
        print(id(localName(e.name())) + ws() + other(":") + ws() +
                other(localName(e.type().toString())));
        
        if(e.getInitExpression().isPresent()){
        	print(ws() + keyword("init") + ws() + other(":") + ws());
        	ExpressionVisitor v = createExpressionVisitor();
        	e.getInitExpression().get().processWithVisitor(v);
        }
        else if(e.isDerived()){
        	print(ws() + keyword("derived") + ws() + other(":") + ws());
        	ExpressionVisitor v = createExpressionVisitor();
        	e.getDeriveExpression().processWithVisitor(v);
        }
        println();
    }

    private void visitAttributesAndOperations( MClassifier e ) {
        // visit attributes
        if (e.attributes().size() > 0 ) {
            indent();
            println(keyword("attributes"));
            incIndent();
            
            MAttribute[] attributes = e.attributes().toArray(new MAttribute[0]);
            Arrays.sort(attributes, new UseFileOrderComparator());
            
        	for (MAttribute attr : attributes) {
				attr.processWithVisitor(this);
			}
            
            decIndent();
        }

        // visit operations
        if (e.operations().size() > 0 ) {
            indent();
            println(keyword("operations"));
            incIndent();
            
            MOperation[] operations = e.operations().toArray(new MOperation[0]);
            Arrays.sort(operations, new UseFileOrderComparator());
            
            for (MOperation op : operations) {
                op.processWithVisitor(this);
            }
            
            decIndent();
        }
    }
    
    @Override
	public void visitClass(MClass e) {
        visitAnnotations(e);
        indent();
        if (e.isAbstract() )
            print(keyword("abstract") + ws());
        print(keyword("class") + ws() + id(localName(e.name())));

        List<MClassifier> parents = sameLevelParents(e);
        if (! parents.isEmpty() ) {
            print(ws() + other("<") + ws() +
                       other(joinLocalNames(parents)));
        }
        println();
        
        visitAttributesAndOperations( e );
        
        indent();
        println(keyword("end")); 
    }

    @Override
    public void visitDataType(MDataType e) {
        visitAnnotations(e);
        indent();
        if (e.isAbstract() )
            print(keyword("abstract") + ws());
        print(keyword("dataType") + ws() + id(localName(e.name())));

        List<MClassifier> parents = sameLevelParents(e);
        if (!parents.isEmpty() ) {
            print(ws() + other("<") + ws() +
                    other(joinLocalNames(parents)));
        }
        println();

        visitAttributesAndOperations(e);

        indent();
        println(keyword("end"));
    }

    @Override
	public void visitClassInvariant(MClassInvariant e) {
    	StringBuilder line = new StringBuilder();
    	line.append(keyword("context"));
    	line.append(ws());
    	
    	if (e.hasVar()) {
    		line.append(id(e.var()));
    		line.append(ws());
    		line.append(other(":"));
    		line.append(ws());
    	}
    	
    	line.append(other(localName(e.cls().name())));
    	
    	if(e.isAnnotated()){
    		println(line.toString());
    		incIndent();
    		visitAnnotations(e);
    		line = new StringBuilder();
    		indent();
    	} else {
    		line.append(ws());
    	}
    	
    	if (e.isExistential()) {
    		line.append(keyword("existential"));
    		line.append(ws());
    	}
    	
    	line.append(keyword("inv"));
    	line.append(ws());
    	line.append(id(localName(e.name())));
    	line.append(other(":"));
        
    	println(line.toString());
        
        incIndent();
        indent();
        ExpressionVisitor visitor = createExpressionVisitor();
        e.bodyExpression().processWithVisitor(visitor);
        println();
        fOut.flush();
        decIndent();
    }

	protected ExpressionVisitor createExpressionVisitor() {
		return new ExpressionPrintVisitor(fOut);
	}

	@Override
	public void visitGeneralization(MGeneralization e) {
    }

    @Override
	public void visitModel(MModel e) {
        visitAnnotations(e);
        indent();
        println(keyword("model") + ws() + id(localName(e.name())));
        println();
    
        // print user-defined data types
        EnumType[] enumTypes = e.enumTypes().toArray(new EnumType[0]);
        Arrays.sort(enumTypes, new UseFileOrderComparator());
        
        for (EnumType t : enumTypes) {
        	visitEnum(t);
        }
        println();

        // visit classes and associations together to maintain USE file order and easy handling of association classes
        Set<MModelElement> classesAndAssocs = new HashSet<MModelElement>();
        classesAndAssocs.addAll(e.classes());
        classesAndAssocs.addAll(e.associations());
        
        List<MModelElement> sortedClassesAndAssocs = new ArrayList<MModelElement>(classesAndAssocs);
        Collections.sort(sortedClassesAndAssocs, new UseModelElementFileOrderComparator());
        
        for(MModelElement element : sortedClassesAndAssocs){
        	element.processWithVisitor(this);
        	println();
        }

        // visit constraints
        indent(); 
        println(keyword("constraints"));

        // invariants
        MClassInvariant[] classInvariants = e.classInvariants().toArray(new MClassInvariant[0]);
        Arrays.sort(classInvariants, new UseFileOrderComparator());
        
        for (MClassInvariant inv : classInvariants) {
            inv.processWithVisitor(this);
            println();
        }

        // pre-/postconditions
        MPrePostCondition[] prePostConditions = e.prePostConditions().toArray(new MPrePostCondition[0]);
        Arrays.sort(prePostConditions, new UseFileOrderComparator());
        
        for (MPrePostCondition ppc : prePostConditions) {
            ppc.processWithVisitor(this);
            println();
        }
    }

    /**
     * Prints one "model X ... " block per level, the
     * "inter-classes"/"inter-associations"/"inter-constraints" sections
     * (only the ones that are non-empty), and one "mediator X &lt; Y ...
     * end" block per mediator -- the actual MLM-USE grammar shape (see
     * any real fixture, e.g. mlm-figure-1-test.use). Each level's own
     * classes/attributes/associations/invariants are visited with that
     * level's name in {@link #fLocalLevels}, so their "Level@" qualifiers
     * print as the bare local names a human would actually write; each
     * mediator's clabjects/assoclinks are visited with both its own level
     * and its parent level in scope, since a clabject line references
     * both ("clabject Child : Parent"). The inter-* sections are printed
     * with no local scope, keeping full qualification since those
     * elements inherently span levels.
     */
    @Override
    public void visitMLM(MMultiLevelModel e) {
        indent();
        println(keyword("MLM") + ws() + id(e.name()));
        println();

        for (MModel level : e.levelsInHierarchyOrder()) {
            fLocalLevels.clear();
            fLocalLevels.add(level.name());
            visitModel(level);
            println();
        }
        fLocalLevels.clear();

        if (!e.interClasses().isEmpty()) {
            indent();
            println(keyword("inter-classes"));
            for (MClass cls : e.interClasses()) {
                cls.processWithVisitor(this);
                println();
            }
        }
        if (!e.interAssociations().isEmpty()) {
            indent();
            println(keyword("inter-associations"));
            for (MAssociation assoc : e.interAssociations()) {
                assoc.processWithVisitor(this);
                println();
            }
        }
        if (!e.interInvariants().isEmpty()) {
            indent();
            println(keyword("inter-constraints"));
            for (MClassInvariant inv : e.interInvariants()) {
                inv.processWithVisitor(this);
                println();
            }
        }

        for (MMediator mediator : e.mediators()) {
            fLocalLevels.clear();
            fLocalLevels.add(mediator.getCurrentModel().name());
            fLocalLevels.add(mediator.parentModelName());
            mediator.processWithVisitor(this);
            println();
        }
        fLocalLevels.clear();
    }

    @Override
	public void visitOperation(MOperation e) {
        visitAnnotations(e);
        indent(); 
        print(id(e.name()) + 
              other("(" + e.paramList() + ")"));
        
        if (e.hasResultType() ) {
            print(ws() + other(":") + ws() + other(e.resultType().toString()));
        }
        
        if (e.hasExpression() ) {
        	println(ws() + other("=") + ws());
            incIndent();
            indent(); 
            ExpressionVisitor visitor = createExpressionVisitor();
            e.expression().processWithVisitor(visitor);
            decIndent();
            println();
        } else if (e.hasStatement()) {
        	println();
        	incIndent();
        	indent();
        	println(keyword("begin"));
        	incIndent();
        	println(getStatementVisitorString(e.getStatement()));
            decIndent();
            indent();
            println(keyword("end"));
            decIndent();
        } else {
        	println();
        }
    }

    
    protected String getStatementVisitorString(MStatement statement) {
    	return statement.toConcreteSyntax(fIndent, fIndentStep);
    }
    
    @Override
	public void visitPrePostCondition(MPrePostCondition e) {
        println(keyword("context") + ws() +
                other(e.cls().name()) + other("::") +
                other(e.operation().signature()));
        incIndent();
        visitAnnotations(e);
        indent();
        print(keyword(e.isPre() ? "pre" : "post") + 
              ws() + id(e.name()) + other(":") + ws());
        
        ExpressionVisitor visitor = createExpressionVisitor();
        e.expression().processWithVisitor(visitor);
        println("");
        decIndent();
    }

    private void incIndent() {
        fIndent += fIndentStep;
    }

    private void decIndent() {
        if (fIndent < fIndentStep )
            throw new RuntimeException("unbalanced indentation");
        fIndent -= fIndentStep;
    }

	@Override
	public void visitAnnotation(MElementAnnotation a) {
		indent();
		print(keyword("@" + a.getName()));
		print("(");
		
		boolean first = true;
		
		for (Map.Entry<String, String> e : a.getValues().entrySet()) {
			if (!first)
				print(", ");
			
			print(id(e.getKey()));
			print("=\"");
			print(e.getValue());
			print("\"");
			
			first = false;
		}
		
		println(")");
	}
	
	private void visitAnnotations(Annotatable e) {
		for (MElementAnnotation a : e.getAllAnnotations().values()) {
			a.processWithVisitor(this);
		}
	}

	@Override
	public void visitSignal(MSignal s) {
		print(keyword("signal"));
		println(s.name());
		incIndent();
		indent();
		print(keyword("attributes"));
		incIndent();
		indent();
		for (MAttribute attr : s.getAttributes()) {
			attr.processWithVisitor(this);
		}
		decIndent();
		decIndent();
		println(keyword("end"));
	}

	@Override
	public void visitEnum(EnumType enumType) {
		visitAnnotations(enumType);
		indent();
		println(keyword("enum") + ws() + other(enumType.name()) + ws() + other("{"));
		
		incIndent();
		indent();
		println(other(StringUtil.fmtSeq(enumType.literals(), ", ")));
		
		decIndent();
		indent();
		println(ws() + other("};"));
	}

    @Override
    public void visitMediator(MMediator mMediator) {
        visitAnnotations(mMediator);
        indent();
        println(keyword("mediator") + ws() + id(mMediator.name()) + ws() + keyword("<") + ws() + id(mMediator.parentModelName()) );
        incIndent();
        visitClabjectsAndAssoclinks(mMediator);
        decIndent();
        println(keyword("end"));
    }

    @Override
    public void visitClabject(MClabject e) {
        visitAnnotations(e);
        indent();
        println(keyword("clabject") + ws() + id(localName(e.child().name())) + ws() + keyword(":") + ws() + id(localName(e.parent().name())));

        incIndent();

        boolean hasAttributeChanges = !e.getAttributeRenaming().isEmpty() || !e.getRemovedAttributes().isEmpty();
        boolean hasRolesChanges = !e.getRemovedRoles().isEmpty();

        if(hasAttributeChanges) {
            indent();
            println(keyword("attributes"));
        }
        incIndent();

        // visit attribute renamings
        for (MAttributeRenaming attrRenaming : e.getAttributeRenaming()) {
            indent();
            println(id(attrRenaming.attribute().name()) + ws() + other("->") + ws() + id(attrRenaming.newName()));
        }

        // visit removed attributes
        for (MAttribute attr : e.getRemovedAttributes()) {
            indent();
            println(other("~") + id(attr.name()));
        }

        decIndent();
        if(hasRolesChanges) {
            indent();
            println(keyword("roles"));
        }
        incIndent();

        // visit role removing
        for (MAssociationEnd role : e.getOnlyClabjectRemovedRoles()) {
            indent();
            println(other("~") + id(role.nameAsRolename()));
        }

        decIndent();
        if (!e.getRemovedConstraints().isEmpty()) {
            indent();
            println(keyword("constraints"));
        }
        incIndent();
        // visit constraint removal
        for (MClassInvariant invariant : e.getRemovedConstraints()) {
            indent();
            println(other("~") + id(localName(invariant.name())));
        }

        decIndent();
        decIndent();
        indent();
        println(keyword("end"));
    }

    @Override
    public void visitAssoclink(MAssoclink e) {
        visitAnnotations(e);
        indent();
        println(keyword("assoclink") + ws() + id(localName(e.child().name())) + ws() + keyword(":") + ws() + id(localName(e.parent().name())));

        incIndent();

        // visit role bindings
        for (MRoleBinding roleBinding : e.roleBindings()) {
            indent();
            println(id(roleBinding.getParentAssociationEnd().nameAsRolename()) + ws() + other("->") + ws() + id(roleBinding.getChildAssociationEnd().nameAsRolename()));
        }

        decIndent();
        indent();
        println(keyword("end"));
    }

    public void visitClabjectsAndAssoclinks(MMediator mMediator) {
        for (MClabject clabject : mMediator.clabjects()) {
            clabject.processWithVisitor(this);
        }

        for(MAssoclink assoclink : mMediator.assocLinks()) {
            assoclink.processWithVisitor(this);
        }
    }

}
