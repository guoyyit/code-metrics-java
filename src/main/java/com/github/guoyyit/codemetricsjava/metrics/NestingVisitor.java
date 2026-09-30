package com.github.guoyyit.codemetricsjava.metrics;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * Maximum nesting depth of control-flow blocks within one method or
 * constructor. Each {@code if/for/foreach/while/do/catch} body contributes
 * one level; {@code else} does not add a level by itself. Nested methods,
 * lambdas and local classes reset the boundary.
 */
final class NestingVisitor extends VoidVisitorAdapter<int[]> {

    private int currentDepth;

    static int of(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return 0;
        }
        NestingVisitor visitor = new NestingVisitor();
        int[] max = {0};
        method.getBody().get().accept(visitor, max);
        return max[0];
    }

    static int of(ConstructorDeclaration constructor) {
        NestingVisitor visitor = new NestingVisitor();
        int[] max = {0};
        constructor.getBody().accept(visitor, max);
        return max[0];
    }

    private void enter(int[] max) {
        currentDepth++;
        max[0] = Math.max(max[0], currentDepth);
    }

    private void leave() {
        currentDepth--;
    }

    @Override
    public void visit(IfStmt node, int[] max) {
        enter(max);
        node.getCondition().accept(this, max);
        node.getThenStmt().accept(this, max);
        node.getElseStmt().ifPresent(elseStmt -> elseStmt.accept(this, max));
        leave();
    }

    @Override
    public void visit(ForStmt node, int[] max) {
        enter(max);
        super.visit(node, max);
        leave();
    }

    @Override
    public void visit(ForEachStmt node, int[] max) {
        enter(max);
        super.visit(node, max);
        leave();
    }

    @Override
    public void visit(WhileStmt node, int[] max) {
        enter(max);
        super.visit(node, max);
        leave();
    }

    @Override
    public void visit(DoStmt node, int[] max) {
        enter(max);
        super.visit(node, max);
        leave();
    }

    @Override
    public void visit(CatchClause node, int[] max) {
        enter(max);
        super.visit(node, max);
        leave();
    }

    @Override
    public void visit(MethodDeclaration node, int[] max) {
        // boundary of the enclosing method
    }

    @Override
    public void visit(ConstructorDeclaration node, int[] max) {
        // boundary of the enclosing constructor
    }

    @Override
    public void visit(LambdaExpr node, int[] max) {
        // lambda bodies are separate executable units
    }

    @Override
    public void visit(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration node,
                      int[] max) {
        // do not descend into local/anonymous classes
    }
}
