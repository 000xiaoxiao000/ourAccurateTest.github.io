package com.oAT.web.domain;

import com.oAT.web.common.ClassStructure;
import org.apache.commons.lang3.ClassUtils;

import java.util.*;
import java.util.stream.Collectors;

public class SourceCodeLayer implements ImageLayer{

    List<ClassStructure> classList;
    Map<String, Integer> weights;
    private final Integer Weight_start = 10; // 初始权重
    private final Integer Weight_unit = 10;

    public SourceCodeLayer(List<ClassStructure> classs) {
        this.classList = classs;
        weights = buildWeights(classs);
    }

    @Override
    public List<ImageElement> elements() {
        List<ImageElement> elements = classList.stream()
                .flatMap(structure -> build(structure).stream())
                .collect(Collectors.toList());
        // 过滤掉 空指向
        List<ImageElement> result = elements.stream().filter(e -> {
            if ("edges".equals(e.group)) {
                if (e.data.target.equals(e.data.source)) {
                    return false;
                }
                // 指向目标必须存在
                return elements.stream().anyMatch(a -> e.data.target.equals(a.data.id));
            }
            return true;
        }).collect(Collectors.toList());
        return result;
    }


    private List<ImageElement> build(ClassStructure structure) {
        List<ImageElement> result = new ArrayList<>();

        // 基础节点
        ImageData classData = new ImageData(structure.getClassName());
        // 基于包名获取权重
        classData.weight = getWeightByClassName(structure.getClassName());
        classData.name = getClassSimpleName(structure.getClassName());
        ImageElement classNode = buildDefaultNode(classData);
        classNode.classes = new String[]{"code_class"};
        result.add(classNode);

        // 继承关系
      /*  Optional.ofNullable(structure.getSuperName()).map(s -> {
            ImageData data = new ImageData(structure.getClassName() + "_extends_" + s);
            data.source = structure.getClassName();
            data.target = s;
            data.weight = 50;
            data.name = "extends";
            ImageElement element = buildDefaultEdge(data);
            element.classes = new String[]{"extends"};
            return element;
        }).ifPresent(a -> {
            result.add(a);
        });
        // 接口实现关系
        Arrays.stream(structure.getInterfaces()).map(i -> {
            ImageData data = new ImageData(structure.getClassName() + "_implements_" + i);
            data.source = structure.getClassName();
            data.target = i;
            data.weight = 35;
            data.name = "implements";
            ImageElement element = buildDefaultEdge(data);
            element.classes = new String[]{"implements"};
            return element;
        }).forEach(a -> result.add(a));
*/
        // 调用关系
        structure.getInvokers().stream().map(i -> {
            ImageData data = new ImageData(structure.getClassName() + "_invoke_" + i.getOwner());
            data.source = structure.getClassName();
            data.target = i.getOwner();
            data.weight = 20;
            data.name = i.getName();
            ImageElement element = buildDefaultEdge(data);
            element.classes = new String[]{"invoke"};
            return element;
        }).forEach(a -> result.add(a));
        return result;
    }

    private Map<String, Integer> buildWeights(List<ClassStructure> classs) {
        Map<String, Integer> map = new HashMap<>();
        List<String> packages = classs.stream()
                .map(a -> a.getClassName())
                .distinct()
                .sorted()
                //.sorted(Collections.reverseOrder(Comparator.comparingInt(o -> o.split("\\.").length)))
                .collect(Collectors.toList());
        if (packages.isEmpty()) {
            return map;
        }
        int bucketSize = Math.max(1, packages.size() / 4);
        for (int i = 0; i < packages.size(); i++) {
            map.put(packages.get(i), i / bucketSize + 1);
        }
        return map;
    }

    private int getWeightByClassName(String packageName) {
        return weights.getOrDefault(packageName, 1) * Weight_unit + Weight_start;
    }

    private static String getClassSimpleName(String className) {
        String packageName = ClassUtils.getPackageName(className);
        return className.replaceAll(packageName + ".", "");
    }

}
