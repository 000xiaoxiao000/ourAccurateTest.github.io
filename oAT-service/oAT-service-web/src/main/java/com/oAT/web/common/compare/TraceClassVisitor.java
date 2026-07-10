package com.oAT.web.common.compare;

import org.objectweb.asm.*;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceAnnotationVisitor;
import org.objectweb.asm.util.TraceFieldVisitor;

public class TraceClassVisitor extends ClassVisitor {

    public final Printer p;
    CompareClass compareClass;

    public TraceClassVisitor() {
        super(Opcodes.ASM5, null);
        this.p = new Textifier();
    }

    @Override
    public void visit(final int version, final int access, final String name,
                      final String signature, final String superName,
                      final String[] interfaces) {
        p.visit(version, access, name, signature, superName, interfaces);
        compareClass = new CompareClass();
        compareClass.setAccess(access);
        compareClass.setMinorVersion(version);
        compareClass.setName(name);
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitSource(final String file, final String debug) {
        p.visitSource(file, debug);
        super.visitSource(file, debug);
    }

    @Override
    public void visitOuterClass(final String owner, final String name,
                                final String desc) {
        p.visitOuterClass(owner, name, desc);
        super.visitOuterClass(owner, name, desc);
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String desc,
                                             final boolean visible) {
        Printer p = this.p.visitClassAnnotation(desc, visible);
        AnnotationVisitor av = cv == null ? null : cv.visitAnnotation(desc,
                visible);
        return new TraceAnnotationVisitor(av, p);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef,
                                                 TypePath typePath, String desc, boolean visible) {
        Printer p = this.p.visitClassTypeAnnotation(typeRef, typePath, desc,
                visible);
        AnnotationVisitor av = cv == null ? null : cv.visitTypeAnnotation(
                typeRef, typePath, desc, visible);
        return new TraceAnnotationVisitor(av, p);
    }

    @Override
    public void visitAttribute(final Attribute attr) {
        p.visitClassAttribute(attr);
        super.visitAttribute(attr);
    }

    @Override
    public void visitInnerClass(final String name, final String outerName,
                                final String innerName, final int access) {
        p.visitInnerClass(name, outerName, innerName, access);
        super.visitInnerClass(name, outerName, innerName, access);
    }

    @Override
    public FieldVisitor visitField(final int access, final String name,
                                   final String desc, final String signature, final Object value) {
        Printer p = this.p.visitField(access, name, desc, signature, value);
        FieldVisitor fv = cv == null ? null : cv.visitField(access, name, desc,
                signature, value);
        return new TraceFieldVisitor(fv, p);
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name,
                                     final String desc, final String signature, final String[] exceptions) {
        Printer p = this.p.visitMethod(access, name, desc, signature,
                exceptions);
        MethodVisitor mv = cv == null ? null : cv.visitMethod(access, name,
                desc, signature, exceptions);
        CompareMethod cm = new CompareMethod(access, name, desc);
        cm.setTexts(p.getText());
        compareClass.add(cm);
        TraceMethodVisitor tmv = new TraceMethodVisitor(mv, p);
        tmv.setCompareMethod(cm);
        return tmv;
    }

    @Override
    public void visitEnd() {
        p.visitClassEnd();
        super.visitEnd();
        StringBuffer stringBuffer;
        for (CompareMethod method : compareClass.getMethods()) {
            stringBuffer =new StringBuffer();
            for (Object text : method.getTexts()) {
                stringBuffer.append(text);
            }
            method.setBody(stringBuffer.toString());
        }
    }

    public CompareClass getCompareClass() {
        return compareClass;
    }
}
