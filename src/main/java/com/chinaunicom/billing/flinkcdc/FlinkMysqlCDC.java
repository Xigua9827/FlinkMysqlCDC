package com.chinaunicom.billing.flinkcdc;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.ververica.cdc.connectors.mysql.MySQLSource;
import com.alibaba.ververica.cdc.connectors.mysql.table.StartupOptions;
import com.alibaba.ververica.cdc.debezium.DebeziumDeserializationSchema;
import com.alibaba.ververica.cdc.debezium.DebeziumSourceFunction;
import io.debezium.data.Envelope;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class FlinkMysqlCDC {
    public static void main(String[] args) throws Exception {

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        Properties properties = new Properties();

        // 配置 Debezium在初始化快照的时候（扫描历史数据的时候） =》 不要锁表
        properties.setProperty("debezium.snapshot.locking.mode", "none");

        env.enableCheckpointing(TimeUnit.SECONDS.toMillis(5));

        CheckpointConfig checkpointConfig = env.getCheckpointConfig();

        //最大同时存在的ck数 和设置的间隔时间有一个就行
        checkpointConfig.setMaxConcurrentCheckpoints(1);
        //超时时间
        checkpointConfig.setCheckpointTimeout(TimeUnit.SECONDS.toMillis(5));
        //2.3 指定从CK自动重启策略
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(2, 5000L));
        //2.4 设置任务关闭的时候保留最后一次CK数据
        checkpointConfig.enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

        DebeziumSourceFunction<String> MysqlSource = MySQLSource.<String>builder()
                .hostname("10.161.116.242")
                .port(3306)
                .deserializer(new MyDeserializationSchema()) //去参数里面找找实现类
                .username("i_billwarning")
                .password("pnOBKUN7zjv9KA8PedkS")
                .databaseList("i_billwarning") // 指定某个特定的库
                .tableList("i_billwarning.tf_f_user_warning_17") //指定特定的表
                .startupOptions(StartupOptions.latest())// 读取binlog策略 这个启动选项有五种
                .debeziumProperties(properties) //配置不要锁表 但是数据一致性不是精准一次 会变成最少一次
                .build();

        /*
         *  .startupOptions(StartupOptions.latest()) 参数配置
         *  1.initial() 全量扫描并且继续读取最新的binlog 最佳实践是第一次使用这个
         *  2.earliest() 从binlog的开头开始读取 就是啥时候开的binlog就从啥时候读
         *  3.latest() 从最新的binlog开始读取
         *  4.specificOffset(String specificOffsetFile, int specificOffsetPos) 指定offset读取
         *  5.timestamp(long startupTimestampMillis) 指定时间戳读取
         * */

        env.addSource(MysqlSource).print();

        env.execute("flink-cdc");

    }


    public static class MyDeserializationSchema implements DebeziumDeserializationSchema<String> {

        @Override
        public void deserialize(SourceRecord sourceRecord, Collector<String> collector) throws Exception {
            Struct value = (Struct) sourceRecord.value();
            Struct after = value.getStruct("after");
            Struct source = value.getStruct("source");

            String db = source.getString("db");//库名
            String table = source.getString("table");//表名

            //获取操作类型 直接将参数穿进去 会自己解析出来 里面是个enum对应每个操作
            /* READ("r"),
               CREATE("c"),
                UPDATE("u"),
                DELETE("d");*/
            Envelope.Operation operation = Envelope.operationFor(sourceRecord);
            String opstr = operation.toString().toLowerCase();
            //类型修正 会把insert识别成create
            if (opstr.equals("create")) {
                opstr = "insert";
            }

            //获取after结构体里面的表数据，封装成json输出
            JSONObject json1 = new JSONObject();
            JSONObject json2 = new JSONObject();
            //加个判空
            if (after != null) {
                List<Field> data = after.schema().fields(); //获取结构体
                for (Field field : data) {
                    String name = field.name(); //结构体的名字
                    Object value2 = after.get(field);//结构体的字段值
                    //放进json2里面去 json2放到json1里面去
                    json2.put(name, value2);
                }
            }


            //整理成大json串输出
            json1.put("db", db);
            json1.put("table", table);
            json1.put("data", json2);
            json1.put("type", opstr);

            collector.collect(json1.toJSONString());

        }

        @Override
        public TypeInformation<String> getProducedType() {
            return TypeInformation.of(String.class);
        }
    }
}
