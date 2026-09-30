package com.github.guoyyit.codemetricsjava.metrics;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * Iterates nested type declarations declared directly inside a type body.
 */
final class BodyMember {

    private final TypeDeclaration<?> declaration;

    private BodyMember(TypeDeclaration<?> declaration) {
        this.declaration = declaration;
    }

    static List<BodyMember> of(TypeDeclaration<?> type) {
        List<BodyMember> nested = new ArrayList<>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member.isTypeDeclaration()) {
                nested.add(new BodyMember(member.asTypeDeclaration()));
            }
        }
        return nested;
    }

    TypeDeclaration<?> declaration() {
        return declaration;
    }
}
