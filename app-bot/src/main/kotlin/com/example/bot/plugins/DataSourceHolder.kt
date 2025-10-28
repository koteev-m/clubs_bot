package com.example.bot.plugins

import javax.sql.DataSource

object DataSourceHolder {
    @Volatile
    var dataSource: DataSource? = null
}
