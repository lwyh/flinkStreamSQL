/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flink.sql.util;

import com.google.common.collect.Lists;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.commons.lang3.StringUtils;
import java.util.List;
import java.util.Map;

import static org.apache.calcite.sql.SqlKind.*;

/**
 * @Auther: jiangjunjie
 * @Date: 2019-06-30 14:57
 * @Description:
 */
public class ParseUtils {
    public static void parseSideWhere(SqlNode whereNode, Map<String, String> physicalFields, List<String> whereConditionList) {
        SqlKind sqlKind = whereNode.getKind();
        if ((sqlKind == SqlKind.OR || sqlKind == SqlKind.AND) && ((SqlBasicCall) whereNode).getOperandList().size() == 2) {
            SqlNode[] sqlOperandsList = ((SqlBasicCall) whereNode).getOperands();
            // whereNode??????????????????or?????????and??????????????????????????????????????????????????????????????????????????????????????????
            parseSideWhere(sqlOperandsList[0], physicalFields, whereConditionList);
            whereConditionList.add(sqlKind.name());
            parseSideWhere(sqlOperandsList[1], physicalFields, whereConditionList);
        } else {
            SqlIdentifier sqlIdentifier = (SqlIdentifier) ((SqlBasicCall) whereNode).getOperands()[0];
            String fieldName = null;
            if (sqlIdentifier.names.size() == 1) {
                fieldName = sqlIdentifier.getComponent(0).getSimple();
            } else {
                fieldName = sqlIdentifier.getComponent(1).getSimple();
            }
            if (physicalFields.containsKey(fieldName)) {
                String sideFieldName = physicalFields.get(fieldName);
                // clone SqlIdentifier node
                SqlParserPos sqlParserPos = new SqlParserPos(0, 0);
                SqlIdentifier sqlIdentifierClone = new SqlIdentifier("", null, sqlParserPos);
                List<String> namesClone = Lists.newArrayList();
                for(String name :sqlIdentifier.names){
                    namesClone.add(name);
                }
                sqlIdentifierClone.setNames(namesClone,null);
                // clone SqlBasicCall node
                SqlBasicCall sqlBasicCall = (SqlBasicCall)whereNode;
                SqlNode[] sqlNodes =  sqlBasicCall.getOperands();
                SqlNode[] sqlNodesClone = new SqlNode[sqlNodes.length];
                for (int i = 0; i < sqlNodes.length; i++) {
                    sqlNodesClone[i] = sqlNodes[i];
                }
                SqlBasicCall sqlBasicCallClone = new SqlBasicCall(sqlBasicCall.getOperator(), sqlNodesClone, sqlParserPos);
                // ??????????????????????????????
                List<String> names = Lists.newArrayList();
                names.add(sideFieldName);
                sqlIdentifierClone.setNames(names, null);

                sqlBasicCallClone.setOperand(0, sqlIdentifierClone);
                whereConditionList.add(sqlBasicCallClone.toString());
            } else {
                // ???????????????????????????????????????????????????????????????
                if (whereConditionList.size() >= 1) {
                    whereConditionList.remove(whereConditionList.size() - 1);
                }
            }
        }
    }

    public static void parseAnd(SqlNode conditionNode, List<SqlNode> sqlNodeList){
        if(conditionNode.getKind() == SqlKind.AND && ((SqlBasicCall)conditionNode).getOperandList().size()==2){
            parseAnd(((SqlBasicCall)conditionNode).getOperands()[0], sqlNodeList);
            sqlNodeList.add(((SqlBasicCall)conditionNode).getOperands()[1]);
        }else{
            sqlNodeList.add(conditionNode);
        }
    }

    public static void parseJoinCompareOperate(SqlNode condition, List<String> sqlJoinCompareOperate) {
        SqlBasicCall joinCondition = (SqlBasicCall) condition;
        if (joinCondition.getKind() == SqlKind.AND) {
            List<SqlNode> operandList = joinCondition.getOperandList();
            for (SqlNode sqlNode : operandList) {
                parseJoinCompareOperate(sqlNode, sqlJoinCompareOperate);
            }
        } else {
            String operator = transformNotEqualsOperator(joinCondition.getKind());
            sqlJoinCompareOperate.add(operator);
        }
    }

    public static String transformNotEqualsOperator(SqlKind sqlKind) {
        if (StringUtils.equalsIgnoreCase(sqlKind.toString(), "NOT_EQUALS")){
            return "!=";
        }
        return sqlKind.sql;
    }

    public static SqlNode replaceJoinConditionTabName(SqlNode conditionNode, Map<String, String> mappingTable) {
        SqlNode[] operands = ((SqlBasicCall) conditionNode).getOperands();

        for (int i = 0; i < operands.length; i++) {
            SqlNode sqlNode = operands[i];
            SqlNode replaceNode = replaceNodeInfo(sqlNode, mappingTable);
            operands[i] = replaceNode;
        }
        return conditionNode;
    }

    /**
     *   m.id covert m_x_0.id
     * @param selectNode
     * @param mapTab
     * @return
     */
    public static SqlNode replaceSelectFieldTabName(SqlNode selectNode, Map<String,String> mapTab) {
        if (selectNode.getKind() == AS) {
            SqlNode leftNode = ((SqlBasicCall) selectNode).getOperands()[0];
            SqlNode replaceNode = replaceSelectFieldTabName(leftNode, mapTab);
            if (replaceNode != null) {
                ((SqlBasicCall) selectNode).getOperands()[0] = replaceNode;
            }

            return selectNode;
        }else if(selectNode.getKind() == IDENTIFIER){
            SqlIdentifier sqlIdentifier = (SqlIdentifier) selectNode;

            if(sqlIdentifier.names.size() == 1){
                return selectNode;
            }

            String newTableName = ParseUtils.getRootName(mapTab, sqlIdentifier.getComponent(0).getSimple());

            if(newTableName == null){
               return  selectNode;
            }
            sqlIdentifier = sqlIdentifier.setName(0, newTableName);
            return sqlIdentifier;

        }else if(selectNode.getKind() == LITERAL || selectNode.getKind() == LITERAL_CHAIN){//????????????
            return selectNode;
        }else if(  AGGREGATE.contains(selectNode.getKind())
                || AVG_AGG_FUNCTIONS.contains(selectNode.getKind())
                || COMPARISON.contains(selectNode.getKind())
                || selectNode.getKind() == OTHER_FUNCTION
                || selectNode.getKind() == DIVIDE
                || selectNode.getKind() == CAST
                || selectNode.getKind() == TRIM
                || selectNode.getKind() == TIMES
                || selectNode.getKind() == PLUS
                || selectNode.getKind() == NOT_IN
                || selectNode.getKind() == OR
                || selectNode.getKind() == AND
                || selectNode.getKind() == MINUS
                || selectNode.getKind() == TUMBLE
                || selectNode.getKind() == TUMBLE_START
                || selectNode.getKind() == TUMBLE_END
                || selectNode.getKind() == SESSION
                || selectNode.getKind() == SESSION_START
                || selectNode.getKind() == SESSION_END
                || selectNode.getKind() == HOP
                || selectNode.getKind() == HOP_START
                || selectNode.getKind() == HOP_END
                || selectNode.getKind() == BETWEEN
                || selectNode.getKind() == IS_NULL
                || selectNode.getKind() == IS_NOT_NULL
                || selectNode.getKind() == CONTAINS
                || selectNode.getKind() == TIMESTAMP_ADD
                || selectNode.getKind() == TIMESTAMP_DIFF

                ){
            SqlBasicCall sqlBasicCall = (SqlBasicCall) selectNode;
            for(int i=0; i<sqlBasicCall.getOperands().length; i++){
                SqlNode sqlNode = sqlBasicCall.getOperands()[i];
                if(sqlNode instanceof SqlLiteral){
                    continue;
                }

                if(sqlNode instanceof SqlDataTypeSpec){
                    continue;
                }

                SqlNode replaceNode = replaceSelectFieldTabName(sqlNode, mapTab);
                if(replaceNode == null){
                    continue;
                }

                sqlBasicCall.getOperands()[i] = replaceNode;
            }

            return selectNode;
        }else if(selectNode.getKind() == CASE){
            System.out.println("selectNode");
            SqlCase sqlCase = (SqlCase) selectNode;
            SqlNodeList whenOperands = sqlCase.getWhenOperands();
            SqlNodeList thenOperands = sqlCase.getThenOperands();
            SqlNode elseNode = sqlCase.getElseOperand();

            replaceWhenOrThenSelectFieldTabName(mapTab, whenOperands);
            replaceWhenOrThenSelectFieldTabName(mapTab, thenOperands);

            ((SqlCase) selectNode).setOperand(3, replaceSelectFieldTabName(elseNode, mapTab));
            return selectNode;
        }else if(selectNode.getKind() == OTHER){
            //?????????
            return selectNode;
        }else{
            throw new RuntimeException(String.format("not support node kind of %s to replace name now.", selectNode.getKind()));
        }
    }

    private static void replaceWhenOrThenSelectFieldTabName(Map<String, String> mapTab, SqlNodeList thenOperands) {
        for(int i=0; i<thenOperands.size(); i++){
            SqlNode oneOperand = thenOperands.get(i);
            SqlNode replaceNode = replaceSelectFieldTabName(oneOperand, mapTab);
            if (replaceNode != null) {
                thenOperands.set(i, replaceNode);
            }
        }
    }

    public static SqlNode replaceNodeInfo(SqlNode parseNode, Map<String, String> mapTab) {
        if (parseNode.getKind() == IDENTIFIER) {
            SqlIdentifier sqlIdentifier = (SqlIdentifier) parseNode;

            String newTableName = ParseUtils.getRootName(mapTab, sqlIdentifier.getComponent(0).getSimple());;

            if (newTableName == null || sqlIdentifier.names.size() == 1) {
                return sqlIdentifier;
            }
            sqlIdentifier = sqlIdentifier.setName(0, newTableName);
            return sqlIdentifier;
        } else if (parseNode instanceof SqlBasicCall) {
            SqlBasicCall sqlBasicCall = (SqlBasicCall) parseNode;
            for (int i = 0; i < sqlBasicCall.getOperandList().size(); i++) {
                SqlNode sqlNode = sqlBasicCall.getOperandList().get(i);
                SqlNode replaceNode = replaceSelectFieldTabName(sqlNode, mapTab);
                sqlBasicCall.getOperands()[i] = replaceNode;
            }

            return sqlBasicCall;
        } else {
            return parseNode;
        }
    }


    public static String getRootName(Map<String, String>  maps, String key) {
        String res = null;
        while (maps.get(key) !=null) {
            res = maps.get(key);
            key = res;
        }
        return res;
    }

    public static void parseLeftNodeTableName(SqlNode leftJoin, List<String> tablesName) {
        if (leftJoin.getKind() == IDENTIFIER) {
            SqlIdentifier sqlIdentifier = (SqlIdentifier) leftJoin;
            tablesName.add(sqlIdentifier.names.get(0));
        } else if (leftJoin.getKind() == AS) {
            SqlNode sqlNode = ((SqlBasicCall) leftJoin).getOperands()[1];
            tablesName.add(sqlNode.toString());
        } else if (leftJoin.getKind() == JOIN) {
            parseLeftNodeTableName(((SqlJoin) leftJoin).getLeft(), tablesName);
            parseLeftNodeTableName(((SqlJoin) leftJoin).getRight(), tablesName);
        }
    }
}
