# Tree Traversals

Methods for visiting all nodes in a tree data structure in a specific order.

### Visual Representation (The Tree)
We'll use this tree for our examples:

```text
       ( A )
      /     \
    ( B )   ( C )
   /     \
 ( D )   ( E )
```

---

## 1. Inorder Traversal (Left, Root, Right)
**Order:** D -> B -> E -> A -> C

### Recursive
```java
public void inorderRecursive(TreeNode root) {
    if (root == null) return;
    inorderRecursive(root.left);
    System.out.println(root.val);
    inorderRecursive(root.right);
}
```

### Iterative
```java
public void inorderIterative(TreeNode root) {
    Stack<TreeNode> stack = new Stack<>();
    TreeNode curr = root;

    while (curr != null || !stack.isEmpty()) {
        while (curr != null) {
            stack.push(curr);
            curr = curr.left;
        }
        curr = stack.pop();
        System.out.println(curr.val);
        curr = curr.right;
    }
}
```

---

## 2. Preorder Traversal (Root, Left, Right)
**Order:** A -> B -> D -> E -> C

### Recursive
```java
public void preorderRecursive(TreeNode root) {
    if (root == null) return;
    System.out.println(root.val);
    preorderRecursive(root.left);
    preorderRecursive(root.right);
}
```

### Iterative
```java
public void preorderIterative(TreeNode root) {
    if (root == null) return;
    Stack<TreeNode> stack = new Stack<>();
    stack.push(root);

    while (!stack.isEmpty()) {
        TreeNode node = stack.pop();
        System.out.println(node.val);
        
        // Push RIGHT first so LEFT is processed first
        if (node.right != null) stack.push(node.right);
        if (node.left != null) stack.push(node.left);
    }
}
```

---

## 3. Postorder Traversal (Left, Right, Root)
**Order:** D -> E -> B -> C -> A

### Recursive
```java
public void postorderRecursive(TreeNode root) {
    if (root == null) return;
    postorderRecursive(root.left);
    postorderRecursive(root.right);
    System.out.println(root.val);
}
```

### Iterative (Two Stacks)
```java
public void postorderIterative(TreeNode root) {
    if (root == null) return;
    Stack<TreeNode> s1 = new Stack<>();
    Stack<TreeNode> s2 = new Stack<>();

    s1.push(root);
    while (!s1.isEmpty()) {
        TreeNode node = s1.pop();
        s2.push(node);
        if (node.left != null) s1.push(node.left);
        if (node.right != null) s1.push(node.right);
    }

    while (!s2.isEmpty()) {
        System.out.println(s2.pop().val);
    }
}
```

### Complexity
- **Time**: $O(V)$ where $V$ is number of nodes.
- **Space**: $O(H)$ where $H$ is the height of the tree.
