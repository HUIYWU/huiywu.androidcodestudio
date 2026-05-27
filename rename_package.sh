#!/bin/bash

# 使用方法：将此脚本放在项目根目录下，赋予执行权限后运行
# chmod +x rename_package.sh
# ./rename_package.sh

set -e  # 遇到错误立即退出

# 旧包名（目录分隔符和点分隔符）
OLD_PATH="com/tom/rv2ide"
OLD_PACKAGE="com.tom.rv2ide"

# 新包名
NEW_PATH="com/huiywu/androidcodestudio"
NEW_PACKAGE="com.huiywu.androidcodestudio"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}即将把包名从 $OLD_PACKAGE 改为 $NEW_PACKAGE${NC}"
echo -e "${YELLOW}注意：请确保已提交或备份当前代码，此操作不可逆！${NC}"
read -p "确认继续？(y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${RED}已取消操作${NC}"
    exit 1
fi

# 步骤1：移动所有符合旧包名的源目录
echo -e "${GREEN}1. 移动目录结构...${NC}"

# 查找所有包含旧包路径的目录（例如 src/main/java/com/tom/rv2ide）
find . -type d -path "*/$OLD_PATH" | while read -r dir; do
    # 计算目标目录：将旧路径替换为新路径
    target_dir="${dir/$OLD_PATH/$NEW_PATH}"
    target_parent=$(dirname "$target_dir")
    
    echo "移动 $dir -> $target_dir"
    mkdir -p "$target_parent"
    mv "$dir" "$target_dir"
    
    # 如果旧目录变空，删除它（保留非空父目录）
    parent_old=$(dirname "$dir")
    if [ -d "$parent_old" ] && [ -z "$(ls -A "$parent_old")" ]; then
        rmdir "$parent_old" 2>/dev/null || true
    fi
done

echo -e "${GREEN}2. 替换文件内容中的包名...${NC}"

# 替换文件内容：.java, .kt, .xml, .gradle, .properties, .pro, .aidl 等
find . -type f \( \
    -name "*.java" -o \
    -name "*.kt" -o \
    -name "*.xml" -o \
    -name "*.gradle" -o \
    -name "*.properties" -o \
    -name "*.pro" -o \
    -name "*.aidl" -o \
    -name "*.kts" \
\) -print0 | while IFS= read -r -d '' file; do
    # 跳过 .git 目录、build 目录、.idea 等
    if [[ "$file" =~ /\.git/ || "$file" =~ /build/ || "$file" =~ /\.gradle/ || "$file" =~ /\.idea/ ]]; then
        continue
    fi
    
    # 检查文件是否为文本文件（避免修改二进制文件）
    if file "$file" | grep -q text; then
        # 使用 sed 替换包名（注意转义点号）
        sed -i.bak "s/$OLD_PACKAGE/$NEW_PACKAGE/g" "$file"
        rm -f "$file.bak"  # 删除备份文件
        echo "已更新: $file"
    fi
done

# 步骤3：处理 AndroidManifest.xml 中的 package 属性（如有必要）
echo -e "${GREEN}3. 检查 AndroidManifest.xml 中的 package 属性...${NC}"
find . -name "AndroidManifest.xml" -type f | while read -r manifest; do
    if grep -q "package=\"$OLD_PACKAGE\"" "$manifest"; then
        sed -i.bak "s/package=\"$OLD_PACKAGE\"/package=\"$NEW_PACKAGE\"/g" "$manifest"
        rm -f "$manifest.bak"
        echo "已更新: $manifest"
    fi
done

# 步骤4：处理 build.gradle 中的 namespace 和 applicationId
echo -e "${GREEN}4. 处理 build.gradle 中的 namespace/applicationId...${NC}"
find . -name "build.gradle" -o -name "build.gradle.kts" | while read -r gradle; do
    if [[ "$gradle" =~ /\.gradle/ ]] || [[ "$gradle" =~ /build/ ]]; then
        continue
    fi
    sed -i.bak -E "s/(namespace|applicationId) ['\"]$OLD_PACKAGE(['\"])/\1 '$NEW_PACKAGE'\2/g" "$gradle"
    rm -f "$gradle.bak"
    echo "已更新: $gradle"
done

echo -e "${GREEN}✅ 包名更改完成！${NC}"
echo -e "${YELLOW}建议执行以下操作："
echo "1. 重新同步 Gradle (./gradlew clean)"
echo "2. 使用 IDE 检查是否有遗漏的引用 (Ctrl+Shift+R 全局搜索 $OLD_PACKAGE)"
echo "3. 更新项目文档或配置文件中可能存在的硬编码包名${NC}"