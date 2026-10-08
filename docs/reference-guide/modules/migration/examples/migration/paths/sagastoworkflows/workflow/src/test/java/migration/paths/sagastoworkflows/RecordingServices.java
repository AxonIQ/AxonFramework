package migration.paths.sagastoworkflows;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static migration.paths.sagastoworkflows.Messages.*;

/** Records requests; tests publish the corresponding result events explicitly. */
class RecordingServices implements ChecksService, ApprovalService {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public void requestCreditCheck(String orderId) { calls.add("credit:" + orderId); }

    @Override
    public void requestStockCheck(String orderId) { calls.add("stock:" + orderId); }

    @Override
    public void requestApproval(String orderId) { calls.add("approval:" + orderId); }
}
