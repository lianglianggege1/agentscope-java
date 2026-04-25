package io.agentscope.core;

//滴滴的面试
public class Test {

    // DAG编排使用的链表
    // 生成任务执行顺序，也即时编排任务
    public static void main(String[] args) {

        Node a = new Node("第一个任务");
        Node b = new Node("第二个任务");
        Node c = new Node("第三个任务");
        Node d = new Node("第四个任务");
        a.next = b;
        b.next = c;
        c.next = d;
        // exec 执行任务
        exec(a);
    }
    public static void exec(Node node) {
        if (node == null) {
            return;
        }
        System.out.println(node.task);
        exec(node.next);
    }

}


class Node {

    public String task; //表示任务的执行

    public Node next; // 表示下一个任务

    public Node pre; // 找头的时候使用的指针

    Node(String task) {
        this.task = task;
    }

}
