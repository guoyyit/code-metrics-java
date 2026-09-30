package com.github.guoyyit.codemetricsjava.metrics;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * McCabe cyclomatic complexity for one method/constructor: 1 plus one for
 * each if/for/foreach/while/do/catch, ternary expression and binary
 * {@code &&} / {@code ||}. {@code case} labels inside classic switch
 * statements add one each; arrow-style switch entries with patterns do as
 * well. Nested methods, lambdas and local classes are separate units.
 */
final class ComplexityVisitor extends VoidVisitorAdapter<int[]> {

    static int of(MethodDeclaration method) {
        int[] counter = {1};
        if (method.getBody().isPresent()) {
            method.getBody().get().accept(new ComplexityVisitor(), counter);
        }
        return counter[0];
    }

    static int of(ConstructorDeclaration constructor) {
        int[] counter = {1};
        constructor.getBody().accept(new ComplexityVisitor(), counter);
        return counter[0];
    }

    @Override
    public void visit(IfStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(ForStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(ForEachStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(WhileStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(DoStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(CatchClause node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.SwitchEntry node, int[] counter) {
        if (!node.getLabels().isEmpty()) {
            counter[0] += node.getLabels().size();
        }
        super.visit(node, counter);
    }

    @Override
    public void visit(ConditionalExpr node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(BinaryExpr node, int[] counter) {
        if (node.getOperator() == BinaryExpr.Operator.AND
                || node.getOperator() == BinaryExpr.Operator.OR) {
            counter[0]++;
        }
        super.visit(node, counter);
    }

    @Override
    public void visit(MethodDeclaration node, int[] counter) {
        // boundary of the enclosing method
    }

    @Override
    public void visit(ConstructorDeclaration node, int[] counter) {
        // boundary of the enclosing constructor
    }

    @Override
    public void visit(LambdaExpr node, int[] counter) {
        // lambda bodies are separate executable units
    }

    @Override
    public void visit(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration node,
                      int[] counter) {
        // do not descend into local/anonymous classes
    }
}
