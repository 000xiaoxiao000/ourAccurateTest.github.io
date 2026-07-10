package com.oAT.web.domain;

import java.util.ArrayList;

public class ImageData {
    public String id;   //包名和类名 + 方法名
    public int weight;
    public String source;
    public String target;
    public String name; //类名 + 方法名
    public String describe; //参数类型和返回类型
    public String packageAndClassName; //包名和类名
    public String methodName; //方法名

    public ArrayList<Integer> doLines;   //某方法的执行行数，如：ImageData类中getSource方法的执行行数为30
    public ArrayList<Integer> lineTotal;   //某方法代码总行数，如：ImageData类中getSource方法的总行数为1
    public float lineRate;

    public ArrayList<Integer> executeMethodTotal = new ArrayList<>();   //执行方法总数
    public ArrayList<Integer> methodTotal =new ArrayList<>();   //方法总数

    public ArrayList<Integer> executebranch = new ArrayList<>();    //执行分支数
    public ArrayList<Integer> branchTotal =new ArrayList<>();   //分支总数

    public int cyclo;   //圈复杂度V(G)
    // 关照关联数
    public int unionCount = 0;
    public String hotName;
    public String[] sqlContents;

    public int x = 0;
    public int y = 0;

    public ImageData(String id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }



    @Override
    public String toString() {
       return "ImageData{" +
                "id='" + id + '\'' +
                ", weight=" + weight +
                ", source='" + source + '\'' +
                ", target='" + target + '\'' +
                ", name='" + name + '\'' +
                ", describe='" + describe + '\'' +
                ", packageAndClassName='" + packageAndClassName + '\'' +
                ", methodName='" + methodName + '\'' +
                ", doLines=" + doLines +
                ", lineTotal=" + lineTotal +
                ", lineRate=" + lineRate +
                ", executeMethodTotal=" + executeMethodTotal +
                ", methodTotal=" + methodTotal +
                ", executebranch=" + executebranch +
                ", branchTotal=" + branchTotal +
                ", cyclo=" + cyclo +
                ", unionCount=" + unionCount +
                ", hotName='" + hotName + '\'' +
                ", sqlContents=" + java.util.Arrays.toString(sqlContents) +
                ", x=" + x +
                ", y=" + y +
                '}';
    }
}
