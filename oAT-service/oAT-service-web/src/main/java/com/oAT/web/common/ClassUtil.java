package com.oAT.web.common;

import com.oAT.web.common.compare.WildcardMatcher;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ClassUtil {

    public static List<ClassStructure> getClassByWar(String filePath, String includeExpr, String excludeExpr) throws IOException {
        try (ZipFileVisit zipFileVisit = new ZipFileVisit(filePath)) {
            return getClassFiles(zipFileVisit, WildcardMatcher.build(includeExpr, excludeExpr));
        }
    }

    private static List<ClassStructure> getClassFiles(ZipFileVisit zipFileVisit, Predicate<String> filter) {
        List<ClassStructure> list = zipFileVisit.streamAndSub("class", "jar")
                .filter(file ->
                        filter.test(toClassName(file.getName()))
                )
                .map(a -> {
                    try {
                        return new ClassStructureParse().buildClass(a.getContent());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).peek(
                        a -> {
                            List<ClassStructure.InvokerMethod> newInvokers = a.getInvokers().stream()
                                    .filter(i -> filter.test(i.getOwner()))
                                    .distinct()
                                    .collect(Collectors.toList());
                            a.setInvokers(newInvokers);
                        }).collect(Collectors.toList());
        return list;
    }

    public static String toClassName(String classFile) {
        String result = classFile.replaceAll("^BOOT-INF/classes/", "");
        result = result.replaceAll("^WEB-INF/classes/", "");
        result = result.replaceAll(".class$", "");
        result = result.replaceAll("/", ".");
//        result=result.replaceAll("/",".");
        return result;
    }

    public static String getClassSimpleName(String className) {
        int i = className.lastIndexOf(".");
        return className.substring(i + 1);
    }

    public static String getMehtodSimpleName(String methodName) {
        methodName = methodName.replaceAll("/", ".");
        if (methodName.lastIndexOf(".") > 0) {
            return methodName.substring(methodName.lastIndexOf(".") + 1);
        }
        return methodName;
    }

}
