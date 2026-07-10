package com.oAT.web.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

public class ClassStructureParse {

    public ClassStructure buildClass(byte[] bytes) throws IOException {
        ClassReader reader = new ClassReader(bytes);
        StructureClassVisit structureClassVisit = new StructureClassVisit();
        reader.accept(structureClassVisit, ClassReader.SKIP_FRAMES);
        return structureClassVisit.structure;
    }

    private class StructureClassVisit extends ClassVisitor {
        ClassStructure structure;

        public StructureClassVisit() {
            super(Opcodes.ASM5);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            ClassStructure structure = new ClassStructure(name.replaceAll("/", "."), access);
            if (interfaces != null) {
                String[] strings = Arrays.stream(interfaces).map(s -> s.replaceAll("/", "."))
                        .collect(Collectors.toList())
                        .toArray(new String[0]);
                structure.setInterfaces(strings);
            }
            if (superName != null) {
                structure.setSuperName(superName.replaceAll("/", "."));
            }
            this.structure = structure;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            return new StructureMethodVisitor(structure, name, desc);
        }
    }

    private class StructureMethodVisitor extends MethodVisitor {
        private final ClassStructure structure;
        private final String sourceMethodName;
        private final String sourceMethodDesc;

        public StructureMethodVisitor(ClassStructure structure, String sourceMethodName, String sourceMethodDesc) {
            super(Opcodes.ASM5);
            this.structure = structure;
            this.sourceMethodName = sourceMethodName;
            this.sourceMethodDesc = sourceMethodDesc;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            structure.addInvokerMethod(owner, name, opcode, sourceMethodName, sourceMethodDesc);
        }
    }
}
