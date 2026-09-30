package com.github.guoyyit.codemetricsjava.metrics;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * Counts statement nodes inside a single method/constructor body. Block
 * statements themselves are not counted; nested methods, lambdas and local
 * classes form separate executable units and are excluded.
 */
final class StatementCounter extends VoidVisitorAdapter<int[]> {

    static int count(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return 0;
        }
        int[] counter = {0};
        method.getBody().get().accept(new StatementCounter(), counter);
        return counter[0];
    }

    static int count(ConstructorDeclaration constructor) {
        int[] counter = {0};
        constructor.getBody().accept(new StatementCounter(), counter);
        return counter[0];
    }

    @Override
    public void visit(BlockStmt node, int[] counter) {
        // block braces are not statements on their own
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.AssertStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.BreakStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.ContinueStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.DoStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.EmptyStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.ExpressionStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.ForEachStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.ForStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.IfStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.LabeledStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.LocalClassDeclarationStmt node, int[] counter) {
        counter[0]++;
        // local class forms a separate executable unit
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt node, int[] counter) {
        counter[0]++;
        // local record forms a separate executable unit
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.ReturnStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.SwitchStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.SwitchEntry node, int[] counter) {
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.SynchronizedStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.ThrowStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.TryStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.UnparsableStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.WhileStmt node, int[] counter) {
        counter[0]++;
        super.visit(node, counter);
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.YieldStmt node, int[] counter) {
        counter[0]++;
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
