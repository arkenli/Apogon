# Local MySQL Test Instance

This file documents the local MySQL instance used for development testing.
It is intentionally ignored by Git.

## Installed Version

- MySQL Server: 8.4.8
- Install path: `C:\Program Files\MySQL\MySQL Server 8.4`

## Runtime

- Host: `127.0.0.1`
- Port: `3307`
- Config file: `C:\aiops-mysql\my.ini`
- Data directory: `C:\aiops-mysql\data`
- Log directory: `C:\aiops-mysql\logs`

This instance is not registered as a Windows service. Start it manually after reboot.

## Database

- Database: `aiops_alert_analysis`
- User: `aiops_test`
- Password: `aiops_test_123`

## JDBC URL

```text
jdbc:mysql://127.0.0.1:3307/aiops_alert_analysis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
```

## Connect With MySQL CLI

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe' --defaults-file=C:\aiops-mysql\my.ini -uaiops_test -paiops_test_123 aiops_alert_analysis
```

## Start

```powershell
& C:\aiops-mysql\start-aiops-mysql.ps1
```

## Stop

```powershell
& C:\aiops-mysql\stop-aiops-mysql.ps1
```

## Health Check

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqladmin.exe' --defaults-file=C:\aiops-mysql\my.ini -uroot ping
```

Expected output:

```text
mysqld is alive
```
